package com.gao.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 复用 LocalLife 后端的登录态。
 *
 * <p>后端登录后把用户信息写进 Redis Hash：{@code login:token:{token}}，
 * 字段 {@code id / nickName / icon}，TTL 30 分钟。agent 直接读这份数据，
 * 不需要用户再登录一次，也不需要信任请求头里的 userId。
 *
 * <p>顺带做两件事：
 * <ol>
 *   <li><b>滑动续期</b>：剩余时间不足一半就重置 TTL，和后端 RefreshTokenInterceptor 一致；</li>
 *   <li><b>按 userId 缓存 token</b>：工具要带着用户身份去调后端接口，但 token 不能让模型传，
 *       所以服务端写一份 {@code agent:token:{userId}}，工具用 memoryId 反查。</li>
 * </ol>
 */
@Slf4j
@Service
public class LocallifeAuthService {

    private static final String TOKEN_CACHE_PREFIX = "agent:token:";

    private final StringRedisTemplate locallifeRedisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public LocallifeAuthService(@Qualifier("locallifeRedisTemplate") StringRedisTemplate locallifeRedisTemplate,
                                StringRedisTemplate stringRedisTemplate) {
        this.locallifeRedisTemplate = locallifeRedisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Value("${agent.locallife.token-prefix:login:token:}")
    private String tokenPrefix;

    @Value("${agent.locallife.token-ttl-seconds:1800}")
    private long tokenTtlSeconds;

    /**
     * token → userId。
     *
     * @return 有效返回 userId；token 为空、不存在或已过期返回 null
     */
    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = tokenPrefix + token;
        Map<Object, Object> entries = locallifeRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            log.debug("token 不存在或已过期");
            return null;
        }
        Long remain = locallifeRedisTemplate.getExpire(key);
        if (remain != null && remain > 0 && remain < tokenTtlSeconds / 2) {
            locallifeRedisTemplate.expire(key, Duration.ofSeconds(tokenTtlSeconds));
            log.debug("登录态续期");
        }
        Object id = entries.get("id");
        if (id == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            log.warn("token 里的 id 不是数字: {}", id);
            return null;
        }
        // 给工具层留一份 token（只存 agent 自己的库）
        stringRedisTemplate.opsForValue()
                .set(TOKEN_CACHE_PREFIX + userId, token, Duration.ofSeconds(tokenTtlSeconds));
        return userId;
    }

    /** 工具层用：按 userId 拿回 token；拿不到返回 null（工具就该拒绝调用后端） */
    public String tokenOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return stringRedisTemplate.opsForValue().get(TOKEN_CACHE_PREFIX + userId);
    }

    /** 取昵称，给问候语用；拿不到就返回 null */
    public String resolveNickName(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Object nick = locallifeRedisTemplate.opsForHash().get(tokenPrefix + token, "nickName");
        return nick == null || String.valueOf(nick).isBlank() ? null : String.valueOf(nick);
    }
}
