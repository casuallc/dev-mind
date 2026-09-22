package com.devmind.knowledge.dto;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55 FR-04 分诊快照 → 徽标视图。
 *
 * <p>本测试同时是<b>存储格式的钉子</b>：{@link #STORED} 就是 triage_json 在库里的样子
 * （record 字段名即 JSON 键）。改字段名会让这个测试和所有历史行一起失效——
 * 那正是要在这里被拦住的事。</p>
 */
class TriageViewsTest {

    /** 一条"模型答了，边车选了 multilingual"的落库快照（照 TriageSnapshot 的真实序列化抄的） */
    private static final String STORED = """
            {"version":1,"degraded":false,"degradedReason":"","model":"multilingual",\
            "routingReason":"non-Latin script (han, 65% of letters)","latencyMs":320,\
            "evidence":{"retrieval":"vector","similarEntries":[\
            {"entryId":11,"entryName":"日志排查","score":0.81}],"note":""},\
            "answers":{\
            "adopt_layer":{"type":"choice","choice":"project",\
            "probabilities":{"global":0.05,"project":0.9,"discard":0.05},"confidence":0.9},\
            "duplicate":{"type":"noul","noul":0.12,"confidence":0.8,"probabilities":{}},\
            "quality":{"type":"score","score":2.0,"probabilities":{"0":0.1,"1":0.1,"2":0.8},\
            "confidence":0.8}}}""";

    /** 降级快照（边车连不上）：有 at 有原因，没有 answers——UI 该显示"没徽标 + 原因" */
    private static final String DEGRADED = """
            {"version":1,"degraded":true,\
            "degradedReason":"决策调用 http://127.0.0.1:8377/v1/predict 失败: ConnectException",\
            "model":"","routingReason":"","latencyMs":3000,\
            "evidence":{"retrieval":"none","similarEntries":[],"note":"检索未装配"},\
            "answers":{}}""";

    private final Instant at = Instant.parse("2026-09-22T10:00:00Z");

    private TriageView of(String json, boolean degradedColumn) {
        return TriageViews.of(json, at, degradedColumn);
    }

    @Test
    void badgeFieldsComeFromStoredAnswers() {
        TriageView view = of(STORED, false);

        assertNotNull(view);
        assertFalse(view.degraded(), view.degradedReason());
        assertEquals(at, view.at());
        assertEquals("multilingual", view.model(), "落库的是边车实际选中的 checkpoint");
        assertTrue(view.routingReason().contains("non-Latin"), view.routingReason());
        assertEquals(320, view.latencyMs());

        assertNotNull(view.adoptLayer());
        assertEquals("project", view.adoptLayer().value());
        assertEquals("采纳到项目", view.adoptLayer().label(), "中文短标签由后端算，前端不认机器值");
        assertEquals(0.9, view.adoptLayer().confidence());
        assertEquals(3, view.adoptLayer().probabilities().size());

        assertNotNull(view.duplicate());
        assertFalse(view.duplicate().duplicate(), "noul=0.12 远低于 0.5");
        assertEquals(0.12, view.duplicate().probability());
        assertEquals(1, view.duplicate().similar().size());
        assertEquals("日志排查", view.duplicate().similar().get(0).entryName());
        assertEquals(0.81, view.duplicate().similar().get(0).score());

        assertNotNull(view.quality());
        assertEquals(2, view.quality().level().intValue());
        assertEquals("直接可用", view.quality().label());
        assertEquals(0.8, view.quality().confidence());

        assertEquals("choice", view.answers().get("adopt_layer").type(), "answers 原样透出给抽屉");
    }

    @Test
    void duplicateFlagRisesAboveHalf() {
        TriageView view = of(STORED.replace("\"noul\":0.12", "\"noul\":0.71"), false);

        assertTrue(view.duplicate().duplicate());
        assertEquals(0.71, view.duplicate().probability());
    }

    @Test
    void neverTriagedIsNullSoTheBadgeBlockIsHidden() {
        assertNull(TriageViews.of(STORED, null, false), "没分诊过就不该有 triage 区块");
        assertNull(TriageViews.of(null, null, false));
    }

    @Test
    void degradedSnapshotKeepsTheReasonAndDropsSuggestions() {
        TriageView view = of(DEGRADED, false);

        assertTrue(view.degraded());
        assertTrue(view.degradedReason().contains("ConnectException"), view.degradedReason());
        assertEquals(3000, view.latencyMs(), "超时那次往返的耗时也要留着——排错时它比原因更有信息量");
        assertNull(view.adoptLayer());
        assertNull(view.duplicate());
        assertNull(view.quality());
        assertTrue(view.answers().isEmpty());
    }

    @Test
    void degradedColumnOverridesStoredSnapshot() {
        TriageView view = of(STORED, true);

        assertTrue(view.degraded(), "列与 JSON 不一致时宁可不显示徽标");
        assertNull(view.adoptLayer());
    }

    @Test
    void blankJsonDegradesInsteadOfReturningNull() {
        TriageView view = of("", false);

        assertNotNull(view, "分诊过（at 有值）就该有区块，只是没建议");
        assertTrue(view.degraded());
        assertEquals("分诊结果缺失", view.degradedReason());
    }

    @Test
    void corruptJsonNeverThrows() {
        TriageView view = of("{\"version\":1,\"answers\":", false);

        assertTrue(view.degraded(), "脏 JSON 不该让 inbox 列表 500");
        assertEquals("分诊结果无法解析", view.degradedReason());
    }

    @Test
    void missingAnswerKeepsTheEvidenceButNoVerdict() {
        String json = STORED.replace("\"duplicate\":{\"type\":\"noul\",\"noul\":0.12,"
                + "\"confidence\":0.8,\"probabilities\":{}}", "\"duplicate\":{\"type\":\"noul\"}");

        TriageView view = of(json, false);

        assertNotNull(view.duplicate());
        assertFalse(view.duplicate().duplicate(), "没答出来就不该假装有结论");
        assertNull(view.duplicate().probability());
        assertEquals(1, view.duplicate().similar().size(), "撞了哪几条仍然要能看");
    }

    @Test
    void unknownModelChoiceEchoesItselfInsteadOfBlankBadge() {
        TriageView view = of(STORED.replace("\"choice\":\"project\"", "\"choice\":\"archive\""), false);

        assertEquals("archive", view.adoptLayer().value());
        assertEquals("archive", view.adoptLayer().label(), "模型造 key 也要看得见，别渲染成空白");
    }

    @Test
    void outOfRangeScoreHasNoLabelAndNoCrash() {
        TriageView view = of(STORED.replace("\"score\":2.0", "\"score\":9.0"), false);

        assertEquals(9, view.quality().level().intValue());
        assertEquals("", view.quality().label());
    }

    @Test
    void retrievalNoteIsSurfacedWithTheDuplicateVerdict() {
        String json = STORED.replace("\"note\":\"\"",
                "\"note\":\"检索降级：未配 embedding，走关键词匹配，重复结论仅供参考\"");

        TriageView view = of(json, false);

        assertTrue(view.duplicate().note().contains("仅供参考"), view.duplicate().note());
    }
}
