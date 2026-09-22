package com.devmind.decision.record.export;

import com.devmind.decision.record.GoldDistributions;
import com.devmind.decision.record.model.DecisionRecordEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-55 FR-05 训练集导出：{@code decision_records} → laya 微调用的 JSONL
 * （每行 {@code {"state":…,"questions":…,"gold":…}} 三字段，人工裁决按题面 criteria 摊成分布）。
 *
 * <p><b>只出三字段</b>：行里塞 capability/refId 之类的元信息看着方便，但训练侧是按 schema 读的，
 * 多一个字段就多一处"哪天被严格校验打回"的风险；追溯能力本来就在库里（记录页按 capability 筛）。</p>
 *
 * <p><b>不可训练的行直接跳过</b>：缺 state/questions（只裁决过、没分诊过）、gold 落不上任何一题
 * （人工动作不构成某道题的答案，如"拒绝提案"）、或快照是脏 JSON。跳过数会回调给调用方——
 * 导出文件比预期短时，第一件要能回答的就是"跳了多少、为什么"。</p>
 */
public final class LayaTrainingJsonl {

    private static final Logger log = LoggerFactory.getLogger(LayaTrainingJsonl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, Object>>> QUESTIONS = new TypeReference<>() {
    };

    private LayaTrainingJsonl() {
    }

    /**
     * 渲染整份导出。
     *
     * @param rows 候选行（调用方已按 capability/since 筛过；这里只做"能不能训"的判断）
     * @return 文本（每行一条，结尾带换行）与产出/跳过条数
     */
    public static Export render(List<DecisionRecordEntity> rows) {
        List<String> lines = new ArrayList<>();
        int skipped = 0;
        for (DecisionRecordEntity row : rows) {
            String line = line(row);
            if (line == null) {
                skipped++;
                continue;
            }
            lines.add(line);
        }
        return new Export(lines.isEmpty() ? "" : String.join("\n", lines) + "\n", lines.size(), skipped);
    }

    /** 单行；不可训练 → null（原因见类注释，逐条记 debug 日志便于对账） */
    static String line(DecisionRecordEntity row) {
        Map<String, Object> state = parse(row.getStateJson(), GENERIC);
        Map<String, Map<String, Object>> questions = parse(row.getQuestionsJson(), QUESTIONS);
        Map<String, Object> gold = parse(row.getGoldJson(), GENERIC);
        if (state == null || state.isEmpty() || questions == null || questions.isEmpty()) {
            log.debug("导出跳过（快照不全）: id={} capability={} ref={}",
                    row.getId(), row.getCapability(), row.getSubjectId());
            return null;
        }
        Map<String, Object> distributions = GoldDistributions.of(questions, gold);
        if (distributions.isEmpty()) {
            log.debug("导出跳过（gold 落不上题面）: id={} capability={} ref={}",
                    row.getId(), row.getCapability(), row.getSubjectId());
            return null;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("state", state);
        line.put("questions", questions);
        line.put("gold", distributions);
        try {
            // state 值里的换行由 Jackson 转义成 \n 字面量，一行一条不会被值里的换行破坏
            return MAPPER.writeValueAsString(line);
        } catch (Exception e) {
            log.warn("导出跳过（序列化失败）: id={} err={}", row.getId(), e.toString());
            return null;
        }
    }

    private static <T> T parse(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.warn("导出跳过（快照解析失败）: {}", e.toString());
            return null;
        }
    }

    /**
     * @param jsonl   导出文本（可空串 = 一条都没产出）
     * @param emitted 产出条数
     * @param skipped 跳过条数（快照不全 / gold 落不上 / 脏 JSON）
     */
    public record Export(String jsonl, int emitted, int skipped) {
    }
}
