-- ============================================================================
-- Agent 项目自己的表（建在 locallife 库里，与后端共用一个数据库）
--
-- kb_chunk 是知识库分块的"正本"：
--   · 向量检索走 Redis（RedisEmbeddingStore）
--   · 关键词检索走这张表的 ngram 全文索引
--   · 两路结果用 RRF 融合
--
-- 为什么不在 MySQL 存向量：MySQL 没有原生向量索引，
-- 8.0 的 FULLTEXT 适合关键词，向量交给 Redis 更合适。
-- ============================================================================

CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_name    VARCHAR(128) NOT NULL COMMENT '来源文档名',
    chunk_index INT          NOT NULL COMMENT '文档内第几块',
    content     TEXT         NOT NULL COMMENT '分块正文',
    content_hash CHAR(32)    NOT NULL COMMENT '正文 MD5，用于幂等',
    version     VARCHAR(32)  NOT NULL COMMENT '知识库指纹',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_hash (content_hash),
    KEY idx_version (version),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 知识库分块（关键词检索用）';

-- 校验：全文索引是否可用
-- SELECT id, content FROM kb_chunk
--  WHERE MATCH(content) AGAINST('锅包肉' IN BOOLEAN MODE) LIMIT 5;
