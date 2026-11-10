package com.gao.agent.retrieval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 关键词召回：靠 MySQL 的 ngram 全文索引。
 *
 * <p><b>为什么需要它</b>：商户名、菜品名（"老厨家""锅包肉"）是专有名词，
 * 向量检索对这类词的召回不稳定 —— 语义相近但名字不同的店容易被排到前面。
 * 关键词检索正好补这一块。
 *
 * <p>查询构造：先按标点把问题切成词，只保留长度 ≥ 2 的，
 * 再用 BOOLEAN MODE 拼成 "词1 词2"（默认 OR），最后按相关度排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordContentRetriever {

    private final JdbcTemplate jdbcTemplate;

    private static final Pattern SPLITTER = Pattern.compile("[\\s,，。？?！!、；;：:（）()\"']+");

    /** 这些词出现频率太高，放进查询只会引入噪声 */
    private static final List<String> STOP_WORDS = List.of(
            "几点", "开门", "关门", "多少", "怎么", "什么", "哪里", "在哪", "有没有", "可以",
            "请问", "一下", "帮我", "我想", "知道", "那家", "这家", "的吗", "呢", "啊");

    public List<Content> retrieve(Query query, int maxResults) {
        String text = query.text();
        String keywords = toBooleanQuery(text);
        if (keywords.isBlank()) {
            log.debug("关键词为空，跳过关键词召回: {}", text);
            return List.of();
        }

        String sql = """
                SELECT doc_name, chunk_index, content,
                       MATCH(content) AGAINST(? IN BOOLEAN MODE) AS score
                FROM kb_chunk
                WHERE MATCH(content) AGAINST(? IN BOOLEAN MODE)
                ORDER BY score DESC
                LIMIT ?
                """;
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, keywords, keywords, maxResults);
        } catch (Exception e) {
            // 表还没建好或全文索引缺失时，关键词路直接降级为空，不影响主链路
            log.warn("关键词召回失败（检查 kb_chunk 表是否已建）: {}", e.getMessage());
            return List.of();
        }

        List<Content> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            TextSegment segment = TextSegment.from(
                    String.valueOf(row.get("content")),
                    Metadata.from(Map.of("docName", String.valueOf(row.get("doc_name")),
                            "chunkIndex", String.valueOf(row.get("chunk_index")))));
            Map<ContentMetadata, Object> metadata = new HashMap<>();
            metadata.put(ContentMetadata.EMBEDDING_ID, "kw-" + row.get("chunk_index"));
            result.add(Content.from(segment, metadata));
        }
        log.debug("关键词召回 {} 条，query={}", result.size(), keywords);
        return result;
    }

    /** 把自然语言问题转成 BOOLEAN MODE 的检索表达式 */
    String toBooleanQuery(String question) {
        if (question == null) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        for (String part : SPLITTER.split(question)) {
            String t = part.trim();
            if (t.length() < 2) {
                continue;
            }
            if (STOP_WORDS.contains(t)) {
                continue;
            }
            tokens.add(t);
        }
        return String.join(" ", tokens);
    }
}
