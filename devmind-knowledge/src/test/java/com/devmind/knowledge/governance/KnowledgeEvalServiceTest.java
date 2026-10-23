package com.devmind.knowledge.governance;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.retrieve.KnowledgeSearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-85 FR-30 检索评测：Recall@K / MRR / 引用准确率 / 拒答率，
 * 以及数据集缺失/损坏时的降级报告。
 */
class KnowledgeEvalServiceTest {

    @TempDir
    Path tempDir;

    private KnowledgeSearchService searchService;
    private KnowledgeProperties props;
    private KnowledgeEvalService service;

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        props = new KnowledgeProperties();
        service = new KnowledgeEvalService(searchService, props, new ObjectMapper());
    }

    @Test
    void computesMetricsAcrossAnswerableAndUnanswerableCases() throws Exception {
        Path dataset = tempDir.resolve("dataset.json");
        Files.writeString(dataset, """
                {
                  "name": "回归集",
                  "topK": 5,
                  "cases": [
                    {"id":"q1","question":"如何部署","answerable":true,
                     "expectedEntryNames":["安装指南"]},
                    {"id":"q2","question":"火星天气","answerable":false},
                    {"id":"q3","question":"重启服务","answerable":true,
                     "expectedChunkContains":["重启服务"]}
                  ]
                }
                """, StandardCharsets.UTF_8);
        props.getEval().setDatasetPath(dataset.toString());
        when(searchService.searchDocuments(any(KnowledgeSearchV2Request.class))).thenAnswer(inv -> {
            KnowledgeSearchV2Request req = inv.getArgument(0);
            return switch (req.query()) {
                case "如何部署" -> response(List.of(
                        hit("其他文档", "无关内容"),
                        hit("安装指南", "部署步骤")));
                case "重启服务" -> response(List.of(
                        hit("运维手册", "步骤：<mark>重启服务</mark>后再观察")));
                default -> response(List.of());
            };
        });

        EvalReport report = service.report(null, null);

        assertEquals("回归集", report.dataset());
        assertEquals(5, report.topK(), "沿用数据集 topK");
        assertEquals(3, report.caseCount());
        assertEquals(2, report.answerableCount());
        assertEquals(1, report.unanswerableCount());
        assertEquals(1.0, report.recallAtK(), 1e-9, "两问都命中");
        assertEquals(0.75, report.mrr(), 1e-9, "命中排名 2 与 1 → (1/2+1)/2");
        assertEquals(0.75, report.citationPrecision(), 1e-9, "两问各命中 1/2、1/1");
        assertEquals(1.0, report.refusalRate(), 1e-9, "不可答问题返回空结果视为拒答");
        assertEquals("NONE", report.degradedReason());

        EvalReport.CaseResult q1 = report.cases().get(0);
        assertEquals(2, q1.firstRelevantRank());
        assertEquals(2, q1.hits().size());
        assertTrue(q1.hits().get(1).relevant());
        assertFalse(q1.hits().get(0).relevant());

        EvalReport.CaseResult q2 = report.cases().get(1);
        assertTrue(q2.refused());
        assertEquals(0.0, q2.reciprocalRank());

        EvalReport.CaseResult q3 = report.cases().get(2);
        assertTrue(q3.hit(), "expectedChunkContains 需先去掉 <mark> 标签再匹配");
        assertEquals(1, q3.firstRelevantRank());
    }

    @Test
    void missingDatasetReturnsDegradedReport() {
        Path missing = tempDir.resolve("nope.json");
        props.getEval().setDatasetPath(missing.toString());

        EvalReport report = service.report(7, 3L);

        assertTrue(report.degradedReason().startsWith("DATASET_NOT_FOUND"), report.degradedReason());
        assertEquals(7, report.topK());
        assertEquals(3L, report.kbId());
        assertTrue(report.cases().isEmpty());
    }

    @Test
    void invalidOrEmptyDatasetIsReported() throws Exception {
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, "{not json", StandardCharsets.UTF_8);
        props.getEval().setDatasetPath(invalid.toString());
        assertTrue(service.report(null, null).degradedReason().startsWith("DATASET_INVALID"));

        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{\"name\":\"空集\",\"cases\":[]}", StandardCharsets.UTF_8);
        props.getEval().setDatasetPath(empty.toString());
        assertEquals("DATASET_EMPTY", service.report(null, null).degradedReason());
    }

    @Test
    void kbFilterIsPassedToSearchAndTopKIsClamped() throws Exception {
        Path dataset = tempDir.resolve("dataset.json");
        Files.writeString(dataset, """
                {"name":"x","cases":[{"question":"q","answerable":true,
                 "expectedEntryNames":["文档"]}]}
                """, StandardCharsets.UTF_8);
        props.getEval().setDatasetPath(dataset.toString());
        when(searchService.searchDocuments(any(KnowledgeSearchV2Request.class))).thenAnswer(inv -> {
            KnowledgeSearchV2Request req = inv.getArgument(0);
            assertEquals(List.of(42L), req.kbIds());
            assertEquals(100, req.topK(), "topK 上限 100");
            return response(List.of(hit("文档", "内容")));
        });

        EvalReport report = service.report(999, 42L);

        assertEquals(100, report.topK());
        assertEquals(42L, report.kbId());
        assertEquals(1.0, report.recallAtK(), 1e-9);
    }

    private static KnowledgeSearchV2Response response(List<KnowledgeSearchV2Response.Hit> hits) {
        return new KnowledgeSearchV2Response(hits, hits.size(), false, "NONE");
    }

    private static KnowledgeSearchV2Response.Hit hit(String title, String snippet) {
        Instant now = Instant.now();
        return new KnowledgeSearchV2Response.Hit(
                1L, 10L, "库", title, "TECH", "p", "s", "摘要", List.of(),
                snippet, 100L, "标题", 0, 10, 0.5, 1, now,
                List.of(new KnowledgeSearchV2Response.Snippet(snippet, 100L, "标题", 0, 10)),
                List.of());
    }
}
