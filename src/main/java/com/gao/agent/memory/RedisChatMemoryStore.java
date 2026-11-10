package com.gao.agent.memory;

import com.gao.agent.util.MemoryId;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆存 Redis：多实例部署时任何一台都能读到同一份上下文。
 *
 * <p>相比原版修了两处：
 * <ol>
 *   <li>首轮对话 Redis 里没有 key，{@code messagesFromJson(null)} 会炸 —— 这里返回空列表；</li>
 *   <li>memoryId 直接当 Redis key 会和其他业务 key 混在一起 —— 这里统一加 {@code chat:memory:} 前缀。</li>
 * </ol>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${agent.memory.ttl-days:1}")
    private long ttlDays;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = stringRedisTemplate.opsForValue().get(key(memoryId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();          // 首轮对话：没有历史，返回空列表而不是抛异常
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key(memoryId), json, Duration.ofDays(ttlDays));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(key(memoryId));
    }

    /** 解析出 userId，供运维排查使用（比如查某个用户的会话） */
    public Long userIdOf(Object memoryId) {
        return MemoryId.userIdOf(String.valueOf(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
