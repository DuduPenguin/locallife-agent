package com.gao.agent.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双路召回：向量 + 关键词，用 RRF 融合。
 *
 * <p><b>为什么不直接把两路分数加权平均</b>：向量分是余弦相似度（0~1），
 * 关键词分是 MySQL 的匹配度（量级完全不同），加权平均得先归一化，
 * 而归一化本身就不稳定 —— 某一路全是 0.9 时另一路就被淹没了。
 *
 * <p><b>RRF（Reciprocal Rank Fusion）只看排名不看分数</b>：
 * <pre>
 *   score(d) = Σ 1 / (k + rank_i(d))     k 一般取 60
 * </pre>
 * 两路都排前面的文档得分最高，只有一路靠前的也能进候选。
 *
 * <p>好处：不需要调权重、不需要归一化、对分数尺度不敏感。
 */
@Slf4j
@Component("contentRetriever")
public class HybridContentRetriever implements ContentRetriever {

    private final ContentRetriever vectorRetriever;
    private final KeywordContentRetriever keywordRetriever;

    @Value("${agent.retrieval.mode:hybrid}")
    private String mode;

    @Value("${agent.retrieval.max-results:5}")
    private int maxResults;

    @Value("${agent.retrieval.candidate-size:15}")
    private int candidateSize;

    @Value("${agent.retrieval.rrf-k:60}")
    private int rrfK;

    private final EmbeddingReranker reranker;

    public HybridContentRetriever(@Qualifier("vectorContentRetriever") ContentRetriever vectorContentRetriever,
                                  KeywordContentRetriever keywordContentRetriever,
                                  EmbeddingReranker reranker) {
        this.vectorRetriever = vectorContentRetriever;
        this.keywordRetriever = keywordContentRetriever;
        this.reranker = reranker;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if ("vector".equalsIgnoreCase(mode)) {
            return vectorRetriever.retrieve(query);
        }

        List<Content> vectorHits = safeRetrieve(query, true);
        List<Content> keywordHits = safeRetrieve(query, false);

        // 用正文当 key 做去重与融合：同一个 chunk 在两路里出现时分数相加
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Content> pool = new LinkedHashMap<>();
        addRanks(vectorHits, scores, pool);
        addRanks(keywordHits, scores, pool);

        // RRF 融合，先多留一些候选给精排
        List<Content> fused = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(maxResults, candidateSize))
                .map(e -> pool.get(e.getKey()))
                .toList();

        // 精排：用 embedding 重算相关度，最终只留 maxResults 条
        fused = reranker.rerank(query.text(), fused);

        log.debug("双路召回：向量 {} 条 + 关键词 {} 条 -> 融合后 {} 条",
                vectorHits.size(), keywordHits.size(), fused.size());
        return fused;
    }

    private List<Content> safeRetrieve(Query query, boolean vector) {
        try {
            if (vector) {
                return vectorRetriever.retrieve(query);
            }
            return keywordRetriever.retrieve(query, candidateSize);
        } catch (Exception e) {
            // 任何一路挂了都不应该让整个问答失败
            log.warn("{} 召回异常，降级为空: {}", vector ? "向量" : "关键词", e.getMessage());
            return List.of();
        }
    }

    private void addRanks(List<Content> contents, Map<String, Double> scores, Map<String, Content> pool) {
        for (int i = 0; i < contents.size(); i++) {
            Content c = contents.get(i);
            String key = c.textSegment().text();
            double rrf = 1.0 / (rrfK + i + 1);      // rank 从 1 开始
            scores.merge(key, rrf, Double::sum);
            pool.putIfAbsent(key, c);
        }
    }

    /** 给评测输出用：当前召回模式 */
    public String currentMode() {
        return mode;
    }

    List<Content> dedup(List<Content> list) {
        Map<String, Content> map = new LinkedHashMap<>();
        for (Content c : list) {
            map.putIfAbsent(c.textSegment().text(), c);
        }
        return new ArrayList<>(map.values());
    }
}
