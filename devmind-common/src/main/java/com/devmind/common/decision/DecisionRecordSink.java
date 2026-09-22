package com.devmind.common.decision;

import java.util.Map;

/**
 * CAP-55 FR-05 决策反馈落库 SPI：把「模型建议 + 人工裁决」这一对存下来，攒成后续微调的
 * gold 数据集（数据飞轮）。实现方 {@code devmind-decision}（{@code decision_records} 表），
 * 消费方（知识库提案分诊）以 {@code ObjectProvider<DecisionRecordSink>} 探测注入——
 * 没装配就不记，不影响业务。
 *
 * <p><b>为什么先建记录再补裁决</b>：模型建议一产生（分诊那一刻）就该落行，否则"用户没点按钮"
 * 和"模型没给建议"就分不出来了，而后者恰恰是要观测的降级率。人工裁决随后 upsert 到同一行。</p>
 *
 * <p><b>写入不该拖慢业务</b>：实现方自行保证失败不抛（记不上日志即可），
 * 调用方在异步/请求线程里都可以直接调，不必 try/catch。</p>
 */
public interface DecisionRecordSink {

    /**
     * 记下（或覆盖）一次模型建议：按 {@code capability + refId} upsert 到同一行——
     * 同一实体重新分诊时，训练样本要的是"最后一次建议 + 最后一次裁决"这一对，
     * 堆历史行只会让导出集里出现互相矛盾的样本。
     *
     * @param capability 能力标识（如 {@code kb-proposal-triage}），导出按它筛
     * @param refId      能力自己的实体 id（训练样本要能追回原实体）
     * @param state      实际发给模型的上下文快照（截断后的那份）
     * @param questions  实际发出的题面（含 criteria——gold 的可选值由它决定）
     * @param result     模型结果（降级也记：降级样本是评估可用性的第一手数据）
     */
    void saveSuggestion(String capability, String refId, Map<String, Object> state,
                        Map<String, Map<String, Object>> questions, DecisionResult result);

    /**
     * 人工裁决落地：upsert 到 {@link #saveSuggestion} 那一行。
     *
     * @param gold 题 id → 人工答案（choice 题给选项名、score 题给等级下标、noul 题给 0/1），
     *             与模型 {@code answers} 同键同值域，导出时转 one-hot 分布
     * @param by   裁决人（用户名），导出留痕用
     */
    void saveVerdict(String capability, String refId, Map<String, Object> gold, String by);
}
