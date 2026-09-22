package com.devmind.knowledge.triage;

import com.devmind.common.decision.DecisionAnswer;
import com.devmind.common.decision.TriageQuestions;
import java.util.List;
import java.util.Map;

/**
 * {@code knowledge_proposals.triage_json} 的结构——落库的那份 JSON 就是这个 record 的序列化。
 *
 * <p><b>只存"模型说了什么"和"当时拿什么判的"</b>：不存展示文案。徽标上的中文由
 * {@code TriageViews} 按 {@link TriageQuestions} 现算，于是改文案只是改代码，
 * 历史行不会因为文案变了就读不懂。这也是 decision_records 能当训练集的前提——
 * 存进去的必须是不随界面变动的事实。</p>
 *
 * <p>{@code answers} 是 laya 应答原样（{@link DecisionAnswer} 序列化即
 * {@code {type, choice, score, noul, confidence, probabilities}}），
 * 抽屉里的「查看依据」直接展示它，不做二次加工。</p>
 */
public record TriageSnapshot(
        int version,
        boolean degraded,
        String degradedReason,
        String model,
        String routingReason,
        long latencyMs,
        Evidence evidence,
        Map<String, DecisionAnswer> answers) {

    public static final int VERSION = 1;

    /**
     * 当时喂给模型的检索依据：重复判定的判据。
     *
     * @param retrieval      {@code vector}（向量通道）| {@code like}（无 embedding 的关键词降级）| {@code none}
     * @param similarEntries 召回到的相似条目（含分数，抽屉里回答"跟谁撞了"）
     * @param note           召回不可用/失败时的说明（有值时徽标上的重复结论要打问号）
     */
    public record Evidence(String retrieval, List<Similar> similarEntries, String note) {

        public record Similar(Long entryId, String entryName, Double score) {
        }
    }

    public DecisionAnswer answer(String qid) {
        return answers == null ? null : answers.get(qid);
    }
}
