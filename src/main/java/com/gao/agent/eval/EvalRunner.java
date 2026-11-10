package com.gao.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索质量评测（离线跑批）。
 *
 * <p>用法：
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--agent.eval.run=true
 * </pre>
 *
 * <p>指标：
 * <ul>
 *   <li><b>Hit@k</b>：前 k 条里至少有一条包含期望关键词的比例</li>
 *   <li><b>MRR</b>：第一条命中排名的倒数的平均值（越接近 1 越好）</li>
 *   <li><b>平均延迟</b>：单次检索耗时</li>
 * </ul>
 *
 * <p>这是后续做「纯向量 vs 双路召回 vs 加 Rerank」三组对比实验的基线工具，
 * 改完参数重跑一次，就能得到一组可写进简历的数字。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.eval.run", havingValue = "true")
public class EvalRunner implements CommandLineRunner {

    private final ContentRetriever contentRetriever;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        List<Map<String, String>> cases = loadCases();
        if (cases.isEmpty()) {
            log.warn("评测集为空，退出");
            return;
        }

        int hitTop1 = 0;
        int hitTop3 = 0;
        int hit = 0;
        double mrrSum = 0;
        long totalMs = 0;
        List<String> misses = new ArrayList<>();

        for (Map<String, String> c : cases) {
            String question = c.get("q");
            String expect = c.get("expect");
            long start = System.currentTimeMillis();
            List<Content> contents = contentRetriever.retrieve(Query.from(question));
            long cost = System.currentTimeMillis() - start;
            totalMs += cost;

            int rank = 0;
            for (int i = 0; i < contents.size(); i++) {
                String text = contents.get(i).textSegment().text();
                if (text.contains(expect)) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank == 1) {
                hitTop1++;
            }
            if (rank > 0 && rank <= 3) {
                hitTop3++;
            }
            if (rank > 0) {
                hit++;
                mrrSum += 1.0 / rank;
            } else {
                misses.add(question + "  (期望包含: " + expect + ")");
            }
        }

        double hitRate = hit * 100.0 / cases.size();
        double mrr = mrrSum / cases.size();
        long avgMs = totalMs / cases.size();

        System.out.println();
        System.out.println("=============== 检索评测报告 ===============");
        System.out.printf("样本数     : %d%n", cases.size());
        System.out.printf("Hit@1      : %.1f%%  (%d/%d)%n", hitTop1 * 100.0 / cases.size(), hitTop1, cases.size());
        System.out.printf("Hit@3      : %.1f%%  (%d/%d)%n", hitTop3 * 100.0 / cases.size(), hitTop3, cases.size());
        System.out.printf("Hit@k      : %.1f%%  (%d/%d)%n", hitRate, hit, cases.size());
        System.out.printf("MRR        : %.3f%n", mrr);
        System.out.printf("平均延迟   : %d ms%n", avgMs);
        if (!misses.isEmpty()) {
            System.out.println("未命中样本 :");
            misses.forEach(m -> System.out.println("  · " + m));
        }
        System.out.println("===========================================");

        // 评测是离线任务，跑完直接退出，避免占着服务端口
        System.exit(0);
    }

    private List<Map<String, String>> loadCases() throws Exception {
        try (InputStream in = new ClassPathResource("eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() {
            });
        }
    }
}
