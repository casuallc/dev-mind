package com.devmind.decisionlab.dataset.dto;

import com.devmind.decision.record.GoldDistributions;
import com.devmind.decisionlab.dataset.CaseGroups;
import com.devmind.decisionlab.dataset.DatasetJson;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CAP-56 实体 → 视图的映射（与 {@code DecisionRecordViews} 同一分工：控制器里不写转换逻辑）。
 *
 * <p>「已标注了哪些题」一律用 {@link GoldDistributions} 的摊分结果判定，而不是"gold 里有这个键"：
 * 原值落不上题面（题面选项改了、等级越界）时那一题其实等于没标，按键判会让覆盖数与真实可评分条数
 * 对不上——评测跑完发现分母少了，却查不出从哪少的。</p>
 */
public final class DatasetViews {

    /** 标题回看用的 state 键（与 KnowledgeTriageService.buildState 同源） */
    private static final String TITLE_KEY = "proposal_title";

    private DatasetViews() {
    }

    public static DatasetView of(DecisionDatasetEntity e) {
        return new DatasetView(e.getId(), e.getName(), e.getKind(), kindLabel(e.getKind()), e.getVersion(),
                e.isFrozen(), e.getQuestionSetVersion(), e.getItemCount(), e.getNote(),
                e.getCreatedBy(), e.getCreatedAt(), e.getFrozenBy(), e.getFrozenAt());
    }

    public static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case DecisionDatasetEntity.KIND_BENCHMARK -> "基准集·人工标注";
            case DecisionDatasetEntity.KIND_REPLAY -> "回流集·决策记录";
            default -> kind == null ? "" : kind;
        };
    }

    public static DatasetItemView item(DecisionDatasetItemEntity e, DatasetJson json) {
        Map<String, Object> state = json.parse(e.getStateJson());
        Map<String, Object> gold = json.parse(e.getGoldJson());
        return itemOf(e, state, json.parseQuestions(e.getQuestionsJson()), gold);
    }

    public static DatasetItemDetail detail(DecisionDatasetItemEntity e, DatasetJson json) {
        Map<String, Object> state = json.parse(e.getStateJson());
        Map<String, Map<String, Object>> questions = json.parseQuestions(e.getQuestionsJson());
        Map<String, Object> gold = json.parse(e.getGoldJson());
        Map<String, Object> distributions = distributions(questions, gold);
        List<String> notLanded = new ArrayList<>();
        for (String key : gold.keySet()) {
            if (!distributions.containsKey(key)) {
                notLanded.add(key);
            }
        }
        return new DatasetItemDetail(itemOf(e, state, questions, gold), state, questions, gold,
                distributions, notLanded);
    }

    /**
     * 人工原值 → 训练/评测共用的分布形状。
     *
     * <p>刻意复用 CAP-55 的 {@link GoldDistributions} 而不是在这里重写一份：训练集导出与评测 gold
     * 必须是同一个换算口径，两处各写一份的话，"导出的训练集"与"评测用的 gold"会在某次改动后悄悄分叉。</p>
     */
    public static Map<String, Object> distributions(Map<String, Map<String, Object>> questions,
                                                    Map<String, Object> gold) {
        return GoldDistributions.of(questions, gold);
    }

    /** 已摊出分布的题 id（冻结覆盖统计与「这条标了几题」共用同一口径） */
    public static List<String> annotated(Map<String, Map<String, Object>> questions, Map<String, Object> gold) {
        return new ArrayList<>(distributions(questions, gold).keySet());
    }

    private static DatasetItemView itemOf(DecisionDatasetItemEntity e, Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions, Map<String, Object> gold) {
        String group = e.getCaseGroup() == null ? CaseGroups.NORMAL : e.getCaseGroup();
        return new DatasetItemView(e.getId(), group, CaseGroups.label(group), e.getSource(),
                e.getOriginRecordId(), e.getNote(), title(state), annotated(questions, gold),
                CaseGroups.holds(group, state).orElse(null), e.getCreatedAt());
    }

    private static String title(Map<String, Object> state) {
        Object title = state.get(TITLE_KEY);
        if (title == null || String.valueOf(title).isBlank()) {
            return "（无标题）";
        }
        String text = String.valueOf(title).replaceAll("\\s+", " ").trim();
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
