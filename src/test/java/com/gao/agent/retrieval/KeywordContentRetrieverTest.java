package com.gao.agent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词查询构造。
 *
 * <p>这段逻辑决定"关键词那一路能不能召回到东西"：
 * 切词错了会搜不到，停止词没过滤会引入一堆噪声。
 * 纯字符串处理，不需要数据库，适合放进 CI。
 */
class KeywordContentRetrieverTest {

    /** toBooleanQuery 不碰 JDBC，这里传 null 即可 */
    private final KeywordContentRetriever retriever = new KeywordContentRetriever(null);

    @Test
    @DisplayName("按空格切词，保留有信息量的词")
    void shouldKeepMeaningfulTokens() {
        assertEquals("锅包肉 老厨家", retriever.toBooleanQuery("锅包肉 老厨家"));
    }

    @Test
    @DisplayName("过滤停止词：几点 / 在哪 / 多少 这类不参与检索")
    void shouldDropStopWords() {
        assertEquals("锅包肉", retriever.toBooleanQuery("锅包肉 在哪 几点"));
    }

    @Test
    @DisplayName("按中文标点切分")
    void shouldSplitByChinesePunctuation() {
        assertEquals("老厨家 锅包肉", retriever.toBooleanQuery("老厨家，锅包肉？"));
    }

    @Test
    @DisplayName("丢掉长度小于 2 的单字")
    void shouldDropSingleChar() {
        assertEquals("张包铺", retriever.toBooleanQuery("张包铺 的 啊"));
    }

    @Test
    @DisplayName("空输入返回空串，不抛异常")
    void shouldHandleEmptyInput() {
        assertEquals("", retriever.toBooleanQuery(""));
        assertEquals("", retriever.toBooleanQuery(null));
        assertEquals("", retriever.toBooleanQuery("   "));
    }

    @Test
    @DisplayName("问题里全是停止词时返回空串（这一路会降级为空结果）")
    void shouldReturnEmptyWhenOnlyStopWords() {
        String query = retriever.toBooleanQuery("几点 开门 多少 在哪");
        assertTrue(query.isBlank(), "应该被过滤成空串，实际: " + query);
    }
}
