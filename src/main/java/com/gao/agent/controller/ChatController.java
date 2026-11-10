package com.gao.agent.controller;

import com.gao.agent.aiservice.MerchantAssistant;
import com.gao.agent.service.LocallifeAuthService;
import com.gao.agent.service.QueryRewriteService;
import com.gao.agent.service.QuotaService;
import com.gao.agent.util.MemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 聊天入口（SSE 流式）。
 *
 * <p>请求处理顺序：
 * <pre>
 *   身份解析（复用 LocalLife 的 token）
 *      ↓
 *   每日配额
 *      ↓
 *   指代改写（有代词时才调模型）
 *      ↓
 *   RAG + 工具 → 流式返回
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ChatController {

    /** 和后端 SystemConstants.TOKEN_HEADER 保持一致 */
    private static final String TOKEN_HEADER = "authorization";

    private final MerchantAssistant merchantAssistant;
    private final LocallifeAuthService authService;
    private final QueryRewriteService rewriteService;
    private final QuotaService quotaService;

    @Value("${agent.auth.allow-header-fallback:true}")
    private boolean allowHeaderFallback;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                             @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
                             @RequestParam("message") String message,
                             @RequestParam(value = "sessionId", required = false) String sessionId) {

        Long userId = authService.resolveUserId(token);
        String source = "token";
        if (userId == null && allowHeaderFallback) {
            userId = headerUserId;
            source = "header";
        }
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "未登录：请先调用 LocalLife 的 /user/login 拿 token");
        }

        // 每日配额
        if (!quotaService.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日提问次数已用完，剩余 " + quotaService.remain(userId) + " 次");
        }

        String memoryId = MemoryId.of(userId, sessionId);
        // 指代改写：只在有代词/短句时触发，避免每轮都多一次模型调用
        String rewritten = rewriteService.rewrite(memoryId, message);
        log.info("提问 userId={} 来源={} memoryId={} 原句={} 改写后={}",
                userId, source, memoryId, message, rewritten);

        return merchantAssistant.chat(memoryId, rewritten)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    log.error("对话失败 memoryId={}", memoryId, e);
                    return Flux.just("抱歉，当前服务繁忙，请稍后再试。");
                });
    }

    /** 身份自检：用 token 换 userId（调试用，不触发模型调用） */
    @GetMapping("/whoami")
    public String whoami(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        Long userId = authService.resolveUserId(token);
        String nick = authService.resolveNickName(token);
        if (userId == null) {
            return "未登录或 token 已过期";
        }
        return "userId=" + userId + "，nickName=" + (nick == null ? "(空)" : nick)
                + "，今日剩余 " + quotaService.remain(userId) + " 次";
    }
}
