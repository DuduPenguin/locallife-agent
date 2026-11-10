package com.gao.agent.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 RAG 的核心零件：会话记忆 + 向量检索器。
 *
 * <p>注意这里只定义<b>向量</b>检索器（bean 名 {@code vectorContentRetriever}）；
 * 真正给 AiService 用的是 {@link com.gao.agent.retrieval.HybridContentRetriever}，
 * 它把向量和关键词两路结果用 RRF 融合（bean 名就叫 {@code contentRetriever}）。
 *
 * <p>召回参数放配置里而不是硬编码，因为要做对比实验：
 * vector / hybrid 两种模式各跑一次评测，就是一组数据。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ChatMemoryStore redisChatMemoryStore;
    private final EmbeddingModel embeddingModel;
    private final RedisEmbeddingStore redisEmbeddingStore;

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    @Value("${agent.retrieval.max-results:5}")
    private int maxResults;

    @Value("${agent.retrieval.min-score:0.6}")
    private double minScore;

    /**
     * 每个会话一份独立的记忆窗口。
     *
     * <p>用 Provider 而不是单个 ChatMemory：多用户并发时各聊各的，不能互相污染。
     * 消息存 Redis，多实例部署也能读到同一份上下文。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                return MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build();
            }
        };
    }

    /**
     * 纯向量检索器（候选来源之一）。
     *
     * <p>minScore 是防幻觉的第一道闸：相似度太低的片段直接丢掉，模型拿不到就不会编。
     */
    @Bean("vectorContentRetriever")
    public ContentRetriever vectorContentRetriever() {
        log.info("向量检索器初始化：maxResults={}, minScore={}", maxResults, minScore);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
