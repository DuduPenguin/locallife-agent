package com.gao.agent.controller;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试接口：只看召回结果，不调对话模型。
 *
 * <p>用途有两个：
 * <ol>
 *   <li>调 RAG 时不用每次都等模型生成，直接看召回对不对；</li>
 *   <li>没有配置对话模型 key 时，也能验证关键词那一路是否工作。</li>
 * </ol>
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class SearchController {

    private final ContentRetriever contentRetriever;

    /**
     * 例：{@code /agent/search?q=老厨家几点开门&limit=5}
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "limit", defaultValue = "5") int limit) {
        List<Content> contents = contentRetriever.retrieve(Query.from(query));
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, contents.size()); i++) {
            Content c = contents.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            String text = c.textSegment().text();
            item.put("text", text.length() > 160 ? text.substring(0, 160) + "…" : text);
            item.put("docName", c.textSegment().metadata().getString("docName"));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }
}
