package com.devmind.knowledge.triage;

import java.util.Map;

/**
 * CAP-55 FR-05 人工裁决事件：提案被采纳/拒绝后，把"人做了什么"交给决策记录落库
 * （{@link ProposalVerdictListener} 在事务提交后处理）。
 *
 * <p><b>为什么走事件而不是直接调 sink</b>：裁决要落在"确实生效了"之后——采纳的事务回滚了，
 * 却留下一行"人已采纳到全局"的 gold，那份训练集就是错的。AFTER_COMMIT 正好表达这个意思。</p>
 *
 * <p><b>gold 在发布前就算好</b>（由 {@code KnowledgeBaseService} 按动作语义给出）：
 * 动作到题面答案的映射是本模块的知识（{@code adopt:project} 对应题面选项 {@code project}），
 * 不该让通用的决策记录层去猜。</p>
 *
 * @param proposalId  提案 id（决策记录的 refId）
 * @param humanAction 动作机器值：{@code adopt:global} / {@code adopt:project} / {@code reject}
 * @param gold        题 id → 人工答案；空 map = 这次裁决不构成任何题的 gold（reject 即如此：
 *                    "拒绝提案"不等于选了"不值得沉淀"，可能只是"现在不采纳"）
 * @param by          裁决人（用户名）
 */
public record ProposalVerdictEvent(long proposalId, String humanAction, Map<String, Object> gold, String by) {
}
