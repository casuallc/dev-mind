package com.devmind.decisionlab.dataset;

import com.devmind.common.decision.TriageQuestions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 回流判定（纯函数）：这条记录能不能变成评测样本、不能的话理由说不说得清、进哪个组。
 *
 * <p>重点在两处容易被"顺手做多"的地方：对照组只认<b>内容能证伪的两个</b>
 * （空召回 / 逐字重复），{@code IRRELEVANT} 故意不自动识别；以及四个拒收理由各归各的，
 * 不合并成一句"样本不合法"。</p>
 */
class RecordIntakeTest {

    private static final String CONTENT = "把日志按天切分并归档，避免单文件过大";
    private static final String OTHER = "容器网络的 MTU 问题会让大包被静默丢掉";

    @Test
    void aRecordWithFullSnapshotAndLandedGoldIsCollectable() {
        RecordIntake.Disposition d = RecordIntake.classify(
                state(CONTENT, "1. 《别的》\n" + OTHER), TriageQuestions.standard(), fullGold());

        assertTrue(d.collectable());
        assertEquals(CaseGroups.NORMAL, d.caseGroup());
        assertEquals("project", d.gold().get(TriageQuestions.Q_LAYER));
    }

    @Test
    void aRecordWithoutHumanAdjudicationIsRejectedAsIncompleteSnapshot() {
        // 只分诊过、还没人裁决：gold 是空的
        assertFalse(RecordIntake.classify(state(CONTENT, "x"), TriageQuestions.standard(), Map.of())
                .collectable());
        assertEquals(RecordIntake.REASON_SNAPSHOT,
                RecordIntake.classify(null, TriageQuestions.standard(), fullGold()).reasonCode());
        assertEquals(RecordIntake.REASON_SNAPSHOT,
                RecordIntake.classify(state(CONTENT, "x"), Map.of(), fullGold()).reasonCode());
    }

    @Test
    void aRecordMeasuredWithAnOlderQuestionSetIsRejectedAndSaysWhatDiffers() {
        Map<String, Object> questions = new LinkedHashMap<>(TriageQuestions.standard());
        questions.put("legacy_question", Map.of("type", "noul"));

        RecordIntake.Disposition d = RecordIntake.classify(state(CONTENT, "1. 《X》\n" + OTHER),
                toQuestions(questions), fullGold());

        assertEquals(RecordIntake.REASON_QUESTION_SET, d.reasonCode());
        assertTrue(d.detail().contains("legacy_question"), "要说清差在哪一题：" + d.detail());
    }

    @Test
    void aRecordWhoseHumanActionIsNotAnAnswerIsRejected() {
        // 人工动作是"拒绝提案"，落不上任何一道题（"拒绝"不等于"不重复"）
        RecordIntake.Disposition d = RecordIntake.classify(state(CONTENT, "1. 《X》\n" + OTHER),
                TriageQuestions.standard(), Map.of(TriageQuestions.Q_LAYER, "PROJECT"));

        assertEquals(RecordIntake.REASON_GOLD, d.reasonCode());
    }

    @Test
    void anEmptyRecallRecordIsAutoDetectedAsTheEmptyRecallControl() {
        // 真实世界里"库里没东西可召回"是常态，这种记录的 state 与构造出来的空召回组逐字同形
        RecordIntake.Disposition d = RecordIntake.classify(
                state(CONTENT, TriageQuestions.EMPTY_RECALL), TriageQuestions.standard(), fullGold());

        assertEquals(CaseGroups.EMPTY_RECALL, d.caseGroup());
    }

    @Test
    void aVerbatimDuplicateRecordIsAutoDetectedAsThatControl() {
        RecordIntake.Disposition d = RecordIntake.classify(
                state(CONTENT, "1. 《日志归档》\n" + CONTENT), TriageQuestions.standard(), fullGold());

        assertEquals(CaseGroups.VERBATIM_DUP, d.caseGroup());
    }

    @Test
    void anOrdinaryRecordIsNotSilentlyTurnedIntoTheIrrelevantControl() {
        // 「召回条目明显不相干」是人的判断，不是"正文不在召回里"这个字符串事实。
        // 自动识别它的话，每一条普通样本都会变成不相关对照组，那个组就再也说明不了任何事情
        RecordIntake.Disposition d = RecordIntake.classify(
                state(CONTENT, "1. 《别的》\n" + OTHER), TriageQuestions.standard(), fullGold());

        assertEquals(CaseGroups.NORMAL, d.caseGroup());
    }

    @Test
    void everyReasonCodeHasAHumanLabel() {
        for (String reason : RecordIntake.REASON_ORDER) {
            assertNotNull(RecordIntake.label(reason));
            assertFalse(reason.equals(RecordIntake.label(reason)), reason + " 要有中文说明");
        }
        assertEquals(List.of(RecordIntake.REASON_SNAPSHOT, RecordIntake.REASON_QUESTION_SET,
                RecordIntake.REASON_GOLD, RecordIntake.REASON_COLLECTED), RecordIntake.REASON_ORDER);
    }

    // ---------------- 夹具 ----------------

    private static Map<String, Object> state(String content, String similarEntries) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal_title", "一些提案");
        state.put(CaseGroups.CONTENT_KEY, content);
        state.put("project", "dev-mind");
        state.put(CaseGroups.SIMILAR_KEY, similarEntries);
        return state;
    }

    private static Map<String, Object> fullGold() {
        Map<String, Object> gold = new LinkedHashMap<>();
        gold.put(TriageQuestions.Q_LAYER, "project");
        gold.put(TriageQuestions.Q_DUPLICATE, false);
        gold.put(TriageQuestions.Q_QUALITY, 1);
        return gold;
    }

    /** {@code TriageQuestions.standard()} 已是目标形状；这里只为把泛型摆平 */
    private static Map<String, Map<String, Object>> toQuestions(Map<String, Object> raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> q = (Map<String, Object>) entry.getValue();
            out.put(entry.getKey(), q);
        }
        return out;
    }
}
