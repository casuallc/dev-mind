package com.devmind.decision.record.dto;

import com.devmind.common.decision.DecisionAnswer;
import com.devmind.decision.record.GoldDistributions;
import com.devmind.decision.record.model.DecisionRecordEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code decision_records} 行 → 视图的映射（记录页读路径）。
 *
 * <p><b>解析失败绝不上抛</b>：记录页是查询页面，一条脏 JSON 只该让那一行的建议/裁决显示为空，
 * 不该让整页 500——这与知识库 inbox 的 triage 视图同一条口径。解析不出的部分按"没有"展示，
 * 该行的 {@code trainable} 也会是 false（导出侧同样跳过）。</p>
 */
public final class DecisionRecordViews {

    private static final Logger log = LoggerFactory.getLogger(DecisionRecordViews.class);

    /** 存储格式的内部读写，不参与对外序列化——故不走 JacksonConfig 的对外口径 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, DecisionAnswer>> ANSWERS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, Object>>> QUESTIONS = new TypeReference<>() {
    };

    private DecisionRecordViews() {
    }

    public static DecisionRecordView of(DecisionRecordEntity e) {
        Map<String, DecisionAnswer> answers = parse(e.getModelAnswer(), ANSWERS);
        Map<String, Object> gold = parse(e.getGoldJson(), GENERIC);
        Map<String, Map<String, Object>> questions = parse(e.getQuestionsJson(), QUESTIONS);
        Map<String, Object> distributions = GoldDistributions.of(questions, gold);
        return new DecisionRecordView(
                e.getId() == null ? 0L : e.getId(),
                e.getCapability(),
                e.getSubjectId(),
                e.isDegraded(),
                e.getDegradedReason() == null ? "" : e.getDegradedReason(),
                e.getLatencyMs() == null ? 0L : e.getLatencyMs(),
                routingField(e.getRoutingJson(), "model"),
                routingField(e.getRoutingJson(), "reason"),
                answers == null ? Map.of() : answers,
                gold == null ? Map.of() : gold,
                e.getHumanAction(),
                agreement(answers, gold),
                trainable(e, distributions),
                e.getDecidedBy(),
                e.getDecidedAt(),
                e.getSuggestedAt(),
                e.getCreatedAt());
    }

    /** 详情：在列表视图之外补上"模型当初看到了什么、被问了什么"（抽屉里逐字回放）。 */
    public static DecisionRecordDetail detail(DecisionRecordEntity e) {
        return new DecisionRecordDetail(of(e),
                orEmpty(parse(e.getStateJson(), GENERIC)),
                parse(e.getQuestionsJson(), QUESTIONS));
    }

    /**
     * 逐题一致性：只有<b>两边都答了</b>的题才有值——"模型没答"或"人没裁决这一题"，
     * 与"答了但不同"是两回事，混在一起会让记录页的准确率看起来比实际差。
     */
    private static Map<String, Boolean> agreement(Map<String, DecisionAnswer> answers,
                                                 Map<String, Object> gold) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (answers == null || gold == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : gold.entrySet()) {
            DecisionAnswer model = answers.get(entry.getKey());
            if (model == null || entry.getValue() == null) {
                continue;
            }
            out.put(entry.getKey(), agrees(model, entry.getValue()));
        }
        return out;
    }

    /** 一致性判定：按原语比对（choice 比选项名、score 比取整后的等级、noul 比是否过半数） */
    private static boolean agrees(DecisionAnswer model, Object gold) {
        return switch (model.type() == null ? "" : model.type()) {
            case "choice" -> model.choice() != null && model.choice().equals(String.valueOf(gold));
            case "score" -> {
                Double value = asNumber(gold);
                yield model.score() != null && value != null
                        && Math.round(model.score()) == Math.round(value);
            }
            case "noul" -> {
                Boolean yes = asYesNo(gold);
                yield model.noul() != null && yes != null && (model.noul() >= 0.5) == yes;
            }
            // 认不出的原语（边车换了协议/旧记录）：不猜，一致性与"有"都不算
            default -> false;
        };
    }

    /** 可训练 = 三份快照齐全 + gold 至少落上一题（与导出侧同一口径，故共用换算） */
    private static boolean trainable(DecisionRecordEntity e, Map<String, Object> distributions) {
        return notBlank(e.getStateJson()) && notBlank(e.getQuestionsJson())
                && notBlank(e.getGoldJson()) && !distributions.isEmpty();
    }

    private static String routingField(String routingJson, String field) {
        Map<String, Object> routing = parse(routingJson, GENERIC);
        Object value = routing == null ? null : routing.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean asYesNo(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        Double numeric = asNumber(value);
        return numeric == null ? null : numeric >= 0.5;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static <T> T parse(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            // 脏 JSON 只让这一行的这部分显示为空；导出侧会因缺快照跳过它
            log.warn("决策记录快照解析失败（按缺失展示）: {}", e.toString());
            return null;
        }
    }
}
