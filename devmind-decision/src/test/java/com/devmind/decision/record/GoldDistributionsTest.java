package com.devmind.decision.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55 FR-05 人工裁决 → gold 分布（导出与"可训练"判定的共用口径）：
 * 分布按键序铺满题面选项，值落不上就丢这一题——宁可不训，也不给模型喂它没见过的标签。
 */
class GoldDistributionsTest {

    private static Map<String, Object> layerQuestion() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("global", "全局");
        criteria.put("project", "项目");
        criteria.put("discard", "放弃");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "choice");
        q.put("criteria", criteria);
        return q;
    }

    private static Map<String, Object> qualityQuestion() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "score");
        q.put("criteria", List.of("含糊不可用", "可用需润色", "直接可用"));
        return q;
    }

    private static Map<String, Object> duplicateQuestion() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "noul");
        return q;
    }

    @Test
    void choiceBecomesOneHotOverTheDeclaredOptions() {
        Map<String, Object> gold = GoldDistributions.of(Map.of("adopt_layer", layerQuestion()),
                Map.of("adopt_layer", "project"));

        Map<String, Object> distribution = asMap(gold.get("adopt_layer"));
        assertEquals(Map.of("global", 0.0, "project", 1.0, "discard", 0.0), distribution);
        assertEquals(List.of("global", "project", "discard"), List.copyOf(distribution.keySet()),
                "顺序必须跟题面 criteria 一致：训练侧按下标对齐标签，顺序一变 gold 就错位");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void unknownOptionIsDroppedInsteadOfInvented() {
        Map<String, Object> gold = GoldDistributions.of(Map.of("adopt_layer", layerQuestion()),
                Map.of("adopt_layer", "archive"));

        assertTrue(gold.isEmpty(), "人给的值不在题面选项里：这题不能训（模型没见过这个标签）");
    }

    @Test
    void scoreAcceptsNumberAndNumericString() {
        Map<String, Map<String, Object>> questions = Map.of("quality", qualityQuestion());

        assertEquals(Map.of("0", 0.0, "1", 0.0, "2", 1.0),
                GoldDistributions.of(questions, Map.of("quality", 2)).get("quality"));
        assertEquals(Map.of("0", 1.0, "1", 0.0, "2", 0.0),
                GoldDistributions.of(questions, Map.of("quality", "0")).get("quality"));
        assertEquals(Map.of("0", 0.0, "1", 1.0, "2", 0.0),
                GoldDistributions.of(questions, Map.of("quality", 1.4)).get("quality"),
                "取整到最近等级");
    }

    @Test
    void scoreOutOfRangeIsDropped() {
        Map<String, Map<String, Object>> questions = Map.of("quality", qualityQuestion());

        assertTrue(GoldDistributions.of(questions, Map.of("quality", 7)).isEmpty());
        assertTrue(GoldDistributions.of(questions, Map.of("quality", "直接可用")).isEmpty(),
                "标签文本不是等级下标：不猜");
    }

    @Test
    void noulIsASingleProbabilityNotADistribution() {
        Map<String, Map<String, Object>> questions = Map.of("duplicate", duplicateQuestion());

        assertEquals(Map.of("noul", 1.0), GoldDistributions.of(questions, Map.of("duplicate", 1)).get("duplicate"));
        assertEquals(Map.of("noul", 0.0), GoldDistributions.of(questions, Map.of("duplicate", false)).get("duplicate"));
        assertEquals(Map.of("noul", 1.0), GoldDistributions.of(questions, Map.of("duplicate", "true")).get("duplicate"));
        assertEquals(Map.of("noul", 0.0), GoldDistributions.of(questions, Map.of("duplicate", 0.2)).get("duplicate"),
                "noul 的人工标签就是「是不是」：0.2 记否");
    }

    @Test
    void partialGoldKeepsOnlyTheAnswerableQuestions() {
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put("adopt_layer", layerQuestion());
        questions.put("quality", qualityQuestion());
        questions.put("duplicate", duplicateQuestion());

        Map<String, Object> gold = GoldDistributions.of(questions, Map.of("adopt_layer", "global"));

        assertEquals(1, gold.size(), "没裁决的题不出分布（reject 那次 gold 为空就是这个形态）");
        assertTrue(gold.containsKey("adopt_layer"));
    }

    @Test
    void emptyInputsYieldNoGold() {
        assertTrue(GoldDistributions.of(null, Map.of("adopt_layer", "global")).isEmpty());
        assertTrue(GoldDistributions.of(Map.of("adopt_layer", layerQuestion()), Map.of()).isEmpty());
        assertTrue(GoldDistributions.of(Map.of("adopt_layer", layerQuestion()), null).isEmpty());
    }

    @Test
    void goldForAQuestionThatWasNotAskedIsIgnored() {
        Map<String, Object> gold = GoldDistributions.of(Map.of("quality", qualityQuestion()),
                Map.of("adopt_layer", "global"));

        assertTrue(gold.isEmpty(), "题面里没有这题就没有可训的标签（导出侧同样跳过该行）");
    }

    @Test
    void malformedQuestionIsSkippedNotGuessed() {
        Map<String, Object> noCriteria = new LinkedHashMap<>();
        noCriteria.put("type", "choice");

        assertNull(GoldDistributions.distribution("choice", null, "global"));
        assertNull(GoldDistributions.distribution("score", null, 1));
        assertNull(GoldDistributions.distribution("unknown_primitive", Map.of("a", "b"), "a"));
    }
}
