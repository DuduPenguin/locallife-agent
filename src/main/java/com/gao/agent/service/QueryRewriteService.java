package com.gao.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多轮指代改写：把「它几点关门」补全成「老厨家几点关门」再送去检索。
 *
 * <p><b>为什么必须有这一步</b>：检索是"无状态"的，它只看当前这句话。
 * 用户第二轮说「它」，检索器根本不知道"它"是谁，召回的结果基本靠运气。
 *
 * <p><b>什么时候触发</b>：只在句子里出现指代词、或者句子特别短时才改写。
 * 否则每轮都多一次模型调用，白白增加延迟和成本。
 */
@Slf4j
@Service
public class QueryRewriteService {

    private static final String[] PRONOUNS = {
            "它", "他", "她", "那家", "这家", "那个", "这个", "那里", "这里", "上面", "刚才", "还有"
    };

    private static final String PROMPT_TEMPLATE = """
            你是检索查询改写器。把用户当前的问题改写成**独立完整**的一句话，
            要求：
            1. 补全代词指代（「它」「那家」要替换成具体的店名或对象）；
            2. 保留原意，不要新增信息，不要回答问题；
            3. 只输出改写后的那一句话，不要加引号、不要解释。

            对话历史（最近几条）：
            %s

            当前问题：%s
            改写结果：""";

    private final ChatMemoryStore redisChatMemoryStore;
    private final ChatModel chatModel;

    public QueryRewriteService(ChatMemoryStore redisChatMemoryStore,
                               @Qualifier("openAiChatModel") ChatModel chatModel) {
        this.redisChatMemoryStore = redisChatMemoryStore;
        this.chatModel = chatModel;
    }

    @Value("${agent.rewrite.enabled:true}")
    private boolean enabled;

    public String rewrite(Object memoryId, String message) {
        if (!enabled || message == null || message.isBlank()) {
            return message;
        }
        if (!needsRewrite(message)) {
            return message;
        }
        List<ChatMessage> history = redisChatMemoryStore.getMessages(memoryId);
        if (history == null || history.isEmpty()) {
            return message;              // 第一轮，没有上下文可补
        }
        String context = format(history);
        try {
            String rewritten = chatModel.chat(PROMPT_TEMPLATE.formatted(context, message)).trim();
            if (rewritten.isBlank() || rewritten.length() > message.length() * 4 + 20) {
                return message;          // 改写结果异常就退回原句
            }
            log.info("指代改写: 「{}」 -> 「{}」", message, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("指代改写失败，使用原句: {}", e.getMessage());
            return message;
        }
    }

    /** 只有出现指代词、或者句子很短（"还有呢""那家呢"）时才改 */
    boolean needsRewrite(String message) {
        if (message.length() <= 6) {
            return true;
        }
        for (String p : PRONOUNS) {
            if (message.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private String format(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 6);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m instanceof UserMessage um) {
                sb.append("用户：").append(um.singleText()).append('\n');
            } else if (m instanceof AiMessage am) {
                String text = am.text();
                if (text != null && !text.isBlank()) {
                    sb.append("助手：")
                            .append(text.length() > 120 ? text.substring(0, 120) + "…" : text)
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }
}
