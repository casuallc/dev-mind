package com.devmind.decision.record.dto;

import com.devmind.common.decision.DecisionAnswer;
import java.time.Instant;
import java.util.Map;

/**
 * CAP-55 FR-05 决策记录视图（记录页列表用）：模型建议与人工裁决并排，附带逐题是否一致。
 *
 * <p><b>本视图不认识任何具体能力的问题语义</b>：题 id、选项名都是原样透出，中文标签由消费方
 * 的前端页给（知识库的题面标签在 {@code TriageQuestions}）。决策模块只做"模型说了什么、
 * 人说了什么、一不一致"这件通用的事——它要服务的是所有后续能力（通知紧急度、失败分类…）。</p>
 *
 * @param capability     能力标识（kb-proposal-triage …）
 * @param refId          能力侧实体 id
 * @param degraded       这次模型建议是降级的（无 answers，只有原因）
 * @param model          边车实际选中的 checkpoint（可空 = 没调成）
 * @param routingReason  边车选它的原因（「查看依据」展示）
 * @param answers        模型逐题答案（原样）
 * @param gold           人工裁决的 gold（题 id → 答案；空 = 这次裁决不构成任何题 gold）
 * @param humanAction    人工动作机器值（adopt:global / reject …）
 * @param agreement      逐题一致性（题 id → 模型与人工是否同答；<b>只有两边都答了才有值</b>，
 *                       单边作答的题在这里不出现——"没可比"不能显示成"不一致"）
 * @param trainable      该行能不能进训练集导出（三份快照齐全且 gold 至少落上一题）
 */
public record DecisionRecordView(
        long id,
        String capability,
        String refId,
        boolean degraded,
        String degradedReason,
        long latencyMs,
        String model,
        String routingReason,
        Map<String, DecisionAnswer> answers,
        Map<String, Object> gold,
        String humanAction,
        Map<String, Boolean> agreement,
        boolean trainable,
        String decidedBy,
        Instant decidedAt,
        Instant suggestedAt,
        Instant createdAt) {
}
