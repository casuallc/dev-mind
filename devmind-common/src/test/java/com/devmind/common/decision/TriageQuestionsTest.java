package com.devmind.common.decision;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55/CAP-56 分诊题面的形状钉子。
 *
 * <p>题面是<b>契约而非文案</b>：题 id 是 decision_records、训练集、评测 gold 三处的连接键，
 * 选项 key 与等级下标是数据的一部分。改了它们，历史行会静默失配（旧 gold 对不上新选项），
 * 所以这里把"不许随手动"的那几处钉死——改动必须让本测试红，逼出一次有意识的决定。</p>
 */
class TriageQuestionsTest {

    @Test
    void questionIdsAndTypesAreFrozen() {
        Map<String, Map<String, Object>> questions = TriageQuestions.standard();
        assertEquals(List.of(TriageQuestions.Q_LAYER, TriageQuestions.Q_DUPLICATE, TriageQuestions.Q_QUALITY),
                List.copyOf(questions.keySet()), "题序即题面顺序，且三题一个都不能少（gold 按题 id 对齐）");
        assertEquals("choice", questions.get(TriageQuestions.Q_LAYER).get("type"));
        assertEquals("noul", questions.get(TriageQuestions.Q_DUPLICATE).get("type"));
        assertEquals("score", questions.get(TriageQuestions.Q_QUALITY).get("type"));
    }

    @Test
    void choiceCriteriaKeysMatchTheAdoptTargets() {
        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) TriageQuestions.standard()
                .get(TriageQuestions.Q_LAYER).get("criteria");
        assertEquals(List.of(TriageQuestions.LAYER_GLOBAL, TriageQuestions.LAYER_PROJECT,
                TriageQuestions.LAYER_DISCARD), List.copyOf(criteria.keySet()),
                "choice 的 criteria key 就是模型能答的值：与落库 tier/采纳 target 同域，不许加也不许改名");
    }

    @Test
    void scoreCriteriaIsAListSoTheIndexIsTheLevel() {
        Object criteria = TriageQuestions.standard().get(TriageQuestions.Q_QUALITY).get("criteria");
        assertEquals(TriageQuestions.QUALITY_LEVELS, criteria,
                "score 的 criteria 是列表（下标即分值，同 QUALITY_LEVELS）；换成 map 会让等级语义变位置");
    }

    @Test
    void noulCarriesNoCriteria() {
        assertFalse(TriageQuestions.standard().get(TriageQuestions.Q_DUPLICATE).containsKey("criteria"),
                "noul 是是非题，没有选项可列——塞 criteria 会让 build_sequence 渲染出多余文本");
    }

    @Test
    void versionIsStampedSoOldReportsAreNotSilentlyReAligned() {
        assertTrue(TriageQuestions.VERSION.contains("kb-proposal-triage"),
                "题面版本要能看出是哪套题面，否则多套题面的报告无法区分");
        assertTrue(TriageQuestions.VERSION.endsWith("@1"),
                "后缀是题面自身的版本号：改题面文案/选项/等级数必须 +1（期间也一并改本断言）");
    }

    @Test
    void emptyRecallPlaceholderStaysVerbatim() {
        assertEquals("（未召回到相似条目）", TriageQuestions.EMPTY_RECALL,
                "CAP-56「空召回」对照组逐字依赖这个串：生产侧（TriageEvidence.stateText）与评测侧共用它，"
                        + "改了它对照组就不再是同一段输入");
    }

    @Test
    void layerLabelPassesUnknownKeysThrough() {
        assertEquals("采纳到全局", TriageQuestions.layerLabel(TriageQuestions.LAYER_GLOBAL));
        assertEquals("", TriageQuestions.layerLabel(null));
        assertEquals("global_v2", TriageQuestions.layerLabel("global_v2"),
                "模型偶尔会造 key：徽标原样回显，别显示空白让人以为没答");
    }

    @Test
    void qualityLabelReadsTheTextBeforeTheColon() {
        assertEquals("含糊不可用", TriageQuestions.qualityLabel(0));
        assertEquals("直接可用", TriageQuestions.qualityLabel(2));
        assertEquals("", TriageQuestions.qualityLabel(3), "越界等级不炸也不瞎编");
        assertEquals("", TriageQuestions.qualityLabel(null));
    }

    @Test
    void shortLabelHandlesBothColonWidths() {
        assertEquals("标签", TriageQuestions.shortLabel("标签：说明"));
        assertEquals("标签", TriageQuestions.shortLabel("标签: 说明"));
        assertEquals("无冒号整条当标签", TriageQuestions.shortLabel("无冒号整条当标签"));
        assertEquals("", TriageQuestions.shortLabel(null));
    }
}
