package com.gao.agent.ingest;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 知识库灌入：切分 → 落 MySQL（关键词检索用）→ 向量化 → 写 Redis（向量检索用）。
 *
 * <p>两条链路要写两份数据，因为检索走的是两条路：
 * <ul>
 *   <li><b>Redis</b>：存向量，用于语义召回；</li>
 *   <li><b>MySQL kb_chunk</b>：存原文 + ngram 全文索引，用于关键词召回。</li>
 * </ul>
 *
 * <p><b>幂等</b>：用「文档内容 MD5 指纹」判断知识库有没有变。
 * 没变就跳过；变了先清旧数据再灌，避免重启一次多一份重复向量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIngestRunner implements CommandLineRunner {

    private static final String VERSION_KEY = "agent:kb:version";

    private final RedisEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Value("${agent.ingest.enabled:true}")
    private boolean enabled;

    @Value("${agent.ingest.chunk-size:500}")
    private int chunkSize;

    @Value("${agent.ingest.chunk-overlap:50}")
    private int chunkOverlap;

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("知识库灌入已关闭，跳过");
            return;
        }

        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");
        if (documents.isEmpty()) {
            log.warn("content 目录下没有文档，跳过灌入");
            return;
        }

        String fingerprint = fingerprintOf(documents);
        String cached = stringRedisTemplate.opsForValue().get(VERSION_KEY);
        if (fingerprint.equals(cached) && chunkCount(fingerprint) > 0) {
            log.info("知识库内容未变化（{}），跳过重复灌入", fingerprint);
            return;
        }

        // 1. 显式切分，保留"来自哪个文档、第几块"
        var splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = new ArrayList<>();
        for (Document doc : documents) {
            String docName = doc.metadata().getString("file_name");
            List<TextSegment> parts = splitter.split(doc);
            for (int i = 0; i < parts.size(); i++) {
                TextSegment seg = parts.get(i);
                String content = seg.text();
                TextSegment withMeta = TextSegment.from(content, Metadata.from(Map.of(
                        "docName", docName == null ? "unknown" : docName,
                        "chunkIndex", String.valueOf(i))));
                segments.add(withMeta);
            }
        }

        // 2. 先清旧的（向量库 + kb_chunk），避免新旧混在一起
        try {
            embeddingStore.removeAll();
        } catch (Exception e) {
            log.warn("清空旧向量失败（不影响本次灌入）: {}", e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM kb_chunk WHERE version <> ?", fingerprint);
        } catch (Exception e) {
            log.warn("清理 kb_chunk 失败（检查表是否已建）: {}", e.getMessage());
        }

        // 3. 写 MySQL：关键词检索的正本
        int sqlRows = insertChunks(segments, fingerprint);

        // 4. 向量化并写 Redis
        List<Embedding> embeddings = new ArrayList<>();
        int batch = 20;
        try {
            for (int i = 0; i < segments.size(); i += batch) {
                List<TextSegment> slice = segments.subList(i, Math.min(i + batch, segments.size()));
                Response<List<Embedding>> resp = embeddingModel.embedAll(slice);
                embeddings.addAll(resp.content());
            }
            embeddingStore.addAll(embeddings, segments);
        } catch (Exception e) {
            // 没配有效的 API-KEY 时这里会失败。灌入失败不该让服务起不来：
            // MySQL 里的分块已经写好了，关键词检索仍可用；向量检索等 key 配好重启再灌。
            log.error("向量化失败（检查 API-KEY 是否有效）: {}", e.getMessage());
            log.error("关键词检索可用，向量检索暂不可用；配置好 key 后重启会自动重灌");
            return;
        }

        stringRedisTemplate.opsForValue().set(VERSION_KEY, fingerprint);
        log.info("知识库灌入完成：{} 个文档 → {} 个分块（MySQL {} 行 + Redis {} 向量），切分 {}/{}，指纹 {}",
                documents.size(), segments.size(), sqlRows, embeddings.size(),
                chunkSize, chunkOverlap, fingerprint);
    }

    private int insertChunks(List<TextSegment> segments, String version) {
        int rows = 0;
        for (TextSegment seg : segments) {
            String content = seg.text();
            String hash = md5(content);
            String docName = seg.metadata().getString("docName");
            String idx = seg.metadata().getString("chunkIndex");
            try {
                rows += jdbcTemplate.update(
                        "INSERT IGNORE INTO kb_chunk (doc_name, chunk_index, content, content_hash, version) VALUES (?,?,?,?,?)",
                        docName == null ? "unknown" : docName,
                        idx == null ? 0 : Integer.parseInt(idx),
                        content, hash, version);
            } catch (Exception e) {
                log.warn("写入 kb_chunk 失败（是否已执行 sql/agent_schema.sql？）: {}", e.getMessage());
                break;
            }
        }
        return rows;
    }

    private int chunkCount(String version) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_chunk WHERE version = ?", Integer.class, version);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 所有文档内容拼起来算 MD5，任何一篇改动都会变 */
    private String fingerprintOf(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (Document doc : documents) {
            sb.append(doc.text());
        }
        return md5(sb.toString());
    }
}
