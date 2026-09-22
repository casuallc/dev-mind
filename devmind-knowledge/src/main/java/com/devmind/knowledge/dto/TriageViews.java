package com.devmind.knowledge.dto;

import com.devmind.common.decision.DecisionAnswer;
import com.devmind.knowledge.triage.TriageQuestions;
import com.devmind.knowledge.triage.TriageSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code triage_json}（模型事实）→ {@link TriageView}（展示形状）的映射。
 *
 * <p><b>解析失败绝不上抛</b>：这段代码在 inbox 列表的读路径上，一条脏 JSON 不该让整个 inbox 500。
 * 真解析不出就降级成"分诊结果无法解析"——用户看到的是"没徽标 + 一句原因"，比红报错强，
 * 而"要不要重跑"仍由人决定。</p>
 *
 * <p>展示文案在这里现算（{@link TriageQuestions} 是唯一来源），落库那份只存模型事实，
 * 于是改文案不会让历史行读不懂。</p>
 */
public final class TriageViews {

    private static final Logger log = LoggerFactory.getLogger(TriageViews.class);

    /** 内部存储格式的读写，不参与对外序列化——故不走 JacksonConfig 的对外口径 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TriageViews() {
    }

    /**
     * @param triageJson     {@code knowledge_proposals.triage_json}
     * @param at             {@code triage_at}
     * @param degradedColumn {@code triage_degraded}（列与 JSON 不一致时以"降级"为准：宁可不显示徽标）
     * @return 从未分诊（at 为空）返回 null——调用方据此不渲染徽标区块
     */
    public static TriageView of(String triageJson, Instant at, boolean degradedColumn) {
        if (at == null) {
            return null;
        }
        if (triageJson == null || triageJson.isBlank()) {
            return TriageView.degraded(at, "分诊结果缺失", 0);
        }
        TriageSnapshot snapshot;
        try {
            snapshot = MAPPER.readValue(triageJson, TriageSnapshot.class);
        } catch (Exception e) {
            log.warn("分诊快照解析失败（按降级展示）: {}", e.toString());
            return TriageView.degraded(at, "分诊结果无法解析", 0);
        }
        boolean degraded = degradedColumn || snapshot.degraded() || snapshot.answers() == null
                || snapshot.answers().isEmpty();
        if (degraded) {
            return TriageView.degraded(at, snapshot.degradedReason(), snapshot.latencyMs());
        }
        return new TriageView(at, false, snapshot.degradedReason(), snapshot.model(),
                snapshot.routingReason(), snapshot.latencyMs(),
                choice(snapshot.answer(TriageQuestions.Q_LAYER)),
                duplicate(snapshot),
                score(snapshot.answer(TriageQuestions.Q_QUALITY)),
                snapshot.answers());
    }

    private static TriageView.ChoiceSuggestion choice(DecisionAnswer answer) {
        if (answer == null || answer.choice() == null) {
            return null;
        }
        return new TriageView.ChoiceSuggestion(answer.choice(),
                TriageQuestions.layerLabel(answer.choice()), answer.confidence(),
                probabilities(answer));
    }

    private static TriageView.ScoreSuggestion score(DecisionAnswer answer) {
        if (answer == null || answer.score() == null) {
            return null;
        }
        Integer level = (int) Math.round(answer.score());
        // 标签用本地题面而不是模型回显的 legend：legend 是可选的，模型没回就成空白徽标
        return new TriageView.ScoreSuggestion(level, TriageQuestions.qualityLabel(level),
                answer.confidence(), probabilities(answer));
    }

    private static TriageView.DuplicateSuggestion duplicate(TriageSnapshot snapshot) {
        DecisionAnswer answer = snapshot.answer(TriageQuestions.Q_DUPLICATE);
        TriageSnapshot.Evidence evidence = snapshot.evidence();
        List<TriageView.SimilarEntry> similar = new ArrayList<>();
        String note = "";
        if (evidence != null) {
            note = evidence.note() == null ? "" : evidence.note();
            if (evidence.similarEntries() != null) {
                for (TriageSnapshot.Evidence.Similar s : evidence.similarEntries()) {
                    similar.add(new TriageView.SimilarEntry(s.entryId(), s.entryName(), s.score()));
                }
            }
        }
        if (answer == null || answer.noul() == null) {
            // 题没答出来：仍有"撞了哪几条"可看，但别假装有重复结论
            return new TriageView.DuplicateSuggestion(false, null, null, similar, note);
        }
        return new TriageView.DuplicateSuggestion(
                answer.noul() >= TriageQuestions.DUPLICATE_THRESHOLD,
                answer.noul(), answer.confidence(), similar, note);
    }

    private static Map<String, Double> probabilities(DecisionAnswer answer) {
        return answer.probabilities() == null ? Map.of() : answer.probabilities();
    }
}
