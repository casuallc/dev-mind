package com.devmind.decision.record.export;

import com.devmind.decision.record.model.DecisionRecordEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55 FR-05 训练集导出：行里只该有 state/questions/gold 三字段、gold 必须是分布形状、
 * 训不了的行（没 gold / 快照不全 / 脏 JSON）一律跳过并计数——导出文件短了要能自证原因。
 */
class LayaTrainingJsonlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };

    private static final String STATE = "{\"proposal_title\":\"构建失败先看日志末尾\"}";
    private static final String QUESTIONS = """
            {"adopt_layer":{"type":"choice","criteria":{"global":"全局","project":"项目","discard":"放弃"}},
             "quality":{"type":"score","criteria":["含糊不可用","可用需润色","直接可用"]}}""";

    private static DecisionRecordEntity row(Long id, String goldJson) {
        DecisionRecordEntity row = new DecisionRecordEntity();
        row.setId(id);
        row.setCapability("kb-proposal-triage");
        row.setSubjectId("42");
        row.setStateJson(STATE);
        row.setQuestionsJson(QUESTIONS);
        row.setGoldJson(goldJson);
        row.setDegraded(false);
        return row;
    }

    @Test
    void lineCarriesExactlyStateQuestionsGold() {
        DecisionRecordEntity row = row(1L, "{\"adopt_layer\":\"project\",\"quality\":1}");

        Map<String, Object> line = MAPPER.readValue(LayaTrainingJsonl.line(row), GENERIC);

        assertEquals(List.of("state", "questions", "gold"), List.copyOf(line.keySet()),
                "训练侧按 schema 读：多一个字段就多一处被严格校验打回的风险");
        assertEquals("构建失败先看日志末尾",
                ((Map<?, ?>) line.get("state")).get("proposal_title"));
        assertEquals(
                Map.of("global", 0.0, "project", 1.0, "discard", 0.0),
                ((Map<?, ?>) line.get("gold")).get("adopt_layer"));
        assertEquals(Map.of("0", 0.0, "1", 1.0, "2", 0.0),
                ((Map<?, ?>) line.get("gold")).get("quality"));
    }

    @Test
    void rowsWithoutGoldAreSkippedAndCounted() {
        DecisionRecordEntity noGold = row(1L, null);
        DecisionRecordEntity unobtainable = row(2L, "{\"quality\":9}");
        DecisionRecordEntity good = row(3L, "{\"quality\":2}");

        LayaTrainingJsonl.Export export = LayaTrainingJsonl.render(List.of(noGold, unobtainable, good));

        assertEquals(1, export.emitted());
        assertEquals(2, export.skipped(), "跳过的原因要能回答：没裁决过 / 裁决落不上题面");
        assertEquals(1, export.jsonl().lines().count(), "一行一条（结尾换行不算多一行）");
        assertTrue(export.jsonl().endsWith("\n"), "JSONL 每行以换行结束，末行也不例外");
    }

    @Test
    void verdictOnlyRowIsSkippedForMissingSnapshots() {
        DecisionRecordEntity row = row(1L, "{\"adopt_layer\":\"global\"}");
        row.setStateJson(null);
        row.setQuestionsJson(null);

        LayaTrainingJsonl.Export export = LayaTrainingJsonl.render(List.of(row));

        assertEquals(0, export.emitted());
        assertEquals(1, export.skipped(), "只裁决过、没分诊过的记录没有输入，训不了");
    }

    @Test
    void corruptSnapshotIsSkippedInsteadOfBlowingUpTheExport() {
        DecisionRecordEntity row = row(1L, "{\"adopt_layer\":\"global\"}");
        row.setStateJson("{不是 JSON");

        LayaTrainingJsonl.Export export = LayaTrainingJsonl.render(List.of(row));

        assertEquals(0, export.emitted());
        assertEquals(1, export.skipped(), "一条脏数据不该让整份导出失败");
    }

    @Test
    void emptyResultIsAnEmptyTextNotANullLine() {
        LayaTrainingJsonl.Export export = LayaTrainingJsonl.render(List.of());

        assertEquals("", export.jsonl());
        assertEquals(0, export.emitted());
        assertFalse(export.jsonl().contains("\n"));
    }

    @Test
    void newlinesInsideStateValuesDoNotBreakTheLineFormat() {
        DecisionRecordEntity row = row(1L, "{\"adopt_layer\":\"global\"}");
        row.setStateJson("{\"proposal_content\":\"第一行\\n第二行\"}");

        String jsonl = LayaTrainingJsonl.render(List.of(row)).jsonl();

        assertEquals(1, jsonl.lines().count(), "值里的换行必须是转义过的：JSONL 的格式契约是一行一条");
        assertTrue(jsonl.contains("\\n"), jsonl);
    }
}
