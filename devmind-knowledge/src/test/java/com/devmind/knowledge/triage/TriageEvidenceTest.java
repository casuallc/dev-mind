package com.devmind.knowledge.triage;

import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.knowledge.KnowledgeRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55 FR-04 的召回证据渲染（喂给 laya 的 state 片段）。
 *
 * <p>重点是<b>空召回那句占位文案</b>：CAP-56 拿它当「空召回」对照组的判据（逐字构造），
 * 所以生产侧与评测侧必须共用 {@link TriageQuestions#EMPTY_RECALL}——本测试就是那条耦合的
 * 报警器，谁把它改回写死字面量、或改动了这个串，这里先红。</p>
 */
class TriageEvidenceTest {

    private static KnowledgeRetriever.RetrievedChunk chunk(int i, String name, String content, double score) {
        return new KnowledgeRetriever.RetrievedChunk((long) i, name, 1L, content, score);
    }

    @Test
    void emptyRecallRendersTheSharedPlaceholderVerbatim() {
        assertEquals(TriageQuestions.EMPTY_RECALL, TriageEvidence.unavailable("").stateText());
    }

    @Test
    void emptyRecallKeepsTheDegradedNoteAfterThePlaceholder() {
        TriageEvidence evidence = TriageEvidence.of(new KnowledgeRetriever.Detailed(
                List.of(), false, KnowledgeRetriever.DegradedReason.NO_EMBEDDING));
        String text = evidence.stateText();
        assertTrue(text.startsWith(TriageQuestions.EMPTY_RECALL),
                "占位句必须在行首：对照组按前缀识别，降级说明只能跟在后头");
        assertTrue(text.contains("关键词匹配"), "降级原因要带出来，否则读的人以为检索是好的");
    }

    @Test
    void emptyRecallWithNoNoteIsJustThePlaceholder() {
        assertEquals(TriageQuestions.EMPTY_RECALL, TriageEvidence.unavailable(null).stateText(),
                "note 为空时不许留尾随空格——逐字比较会因此失配");
    }

    @Test
    void recalledChunksAreNumberedWithNamesAndSimilarity() {
        TriageEvidence evidence = TriageEvidence.of(new KnowledgeRetriever.Detailed(
                List.of(chunk(1, "部署坑", "Windows 下要 taskkill", 0.87),
                        chunk(2, "H2 坑", "保留字不能当列名", 0.42)),
                true, KnowledgeRetriever.DegradedReason.NONE));
        String text = evidence.stateText();
        assertFalse(text.startsWith(TriageQuestions.EMPTY_RECALL), "有召回就不是空召回组");
        assertTrue(text.contains("1. 《部署坑》 相似度 0.87"));
        assertTrue(text.contains("2. 《H2 坑》 相似度 0.42"));
    }

    @Test
    void likeFallbackOmitsSimilarityBecauseScoreIsMeaninglessThere() {
        TriageEvidence evidence = TriageEvidence.of(new KnowledgeRetriever.Detailed(
                List.of(chunk(1, "关键词命中", "内容", 0.0)),
                false, KnowledgeRetriever.DegradedReason.NO_EMBEDDING));
        String text = evidence.stateText();
        assertTrue(text.contains("1. 《关键词命中》"));
        assertFalse(text.contains("相似度"), "LIKE 降级的 score 恒 0，写出来只会让人误判成'完全不像'");
    }

    @Test
    void longChunksAreTruncatedSoTheThirdOneSurvives() {
        TriageEvidence evidence = TriageEvidence.of(new KnowledgeRetriever.Detailed(
                List.of(chunk(1, "长条目", "x".repeat(2000), 0.9)),
                true, KnowledgeRetriever.DegradedReason.NONE));
        String text = evidence.stateText();
        assertTrue(text.contains("…（已截断）"));
        assertTrue(text.length() < 600, "单条截 300 字：state 有总闸，不截会把后面的条目整体挤掉");
    }
}
