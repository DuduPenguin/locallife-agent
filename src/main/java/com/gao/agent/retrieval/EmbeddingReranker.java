package com.gao.agent.retrieval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 精排（Rerank）：召回阶段拿得多、精排阶段拿得准。
 *
 * <pre>
 *   召回 top15（向量 + 关键词 RRF）
 *        ↓  这里
 *   精排 top5（用 embedding 重算 query 与候选的相关度）
 * </pre>
 *
 * <p><b>为什么还要精排</b>：召回是为了"不漏"，所以候选多、每条算得粗；
 * 精排是为了"排序准"，候选少、每条算得细。两阶段分开才好调参。
 *
 * <p><b>为什么用 embedding 而不是 cross-encoder</b>：本机没有 cross-encoder 模型，
 * 而 embedding 接口已经有了；用 query 与候选块重新算余弦相似度，
 * 能修正 RRF 只看排名的粗糙。代价是每次查询多一次 embedding 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingReranker {

    private final EmbeddingModel embeddingModel;

    @Value("${agent.rerank.enabled:true}")
    private boolean enabled;

    @Value("${agent.rerank.top-n:5}")
    private int topN;

    public List<Content> rerank(String query, List<Content> candidates) {
        if (!enabled || candidates == null || candidates.size() <= topN) {
            return candidates;
        }
        try {
            List<TextSegment> segments = new ArrayList<>();
            segments.add(TextSegment.from(query));
            for (Content c : candidates) {
                segments.add(TextSegment.from(c.textSegment().text()));
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            Embedding queryEmbedding = embeddings.get(0);

            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double sim = CosineSimilarity.between(queryEmbedding, embeddings.get(i + 1));
                scored.add(new Scored(candidates.get(i), sim));
            }
            scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

            List<Content> result = scored.stream().limit(topN).map(s -> s.content).toList();
            log.debug("精排 {} 条 -> {}", candidates.size(), result.size());
            return result;
        } catch (Exception e) {
            // 精排失败不影响主链路：直接用召回结果
            log.warn("精排失败，沿用召回结果: {}", e.getMessage());
            return candidates.size() > topN ? candidates.subList(0, topN) : candidates;
        }
    }

    private record Scored(Content content, double score) {
    }
}
