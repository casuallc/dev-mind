package com.devmind.knowledge.dto;

import com.devmind.common.decision.DecisionAnswer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CAP-55 FR-04 分诊建议视图（inbox 徽标 + 「查看依据」抽屉的数据源）。
 *
 * <p>已经是<b>展示形状</b>：中文短标签、重复布尔判定都在这儿算好了，前端不解析模型应答、
 * 也不需要知道 laya 的 choice/score/noul 是什么。机器值（选项 key、等级下标）原样保留在
 * {@code value}/{@code level} 与 {@code probabilities} 里，训练口径与历史对齐用得到。</p>
 *
 * @param at             最近一次分诊时间
 * @param degraded       true = 没拿到建议（边车没配/连不上/应答不可解析），三块建议均为 null
 * @param degradedReason 降级原因（degraded=false 时空串）
 * @param model          边车实际选中的 checkpoint（端点没配 checkpoint 时由边车路由）
 * @param routingReason  边车选它的理由（抽屉里展示，可能为空）
 * @param latencyMs      这次决策往返耗时
 * @param adoptLayer     采纳层级建议
 * @param duplicate      重复风险建议（含召回依据）
 * @param quality        质量分建议
 * @param answers        laya 应答原文（题 id → 答案），抽屉里「查看依据」直接展示
 */
public record TriageView(
        Instant at,
        boolean degraded,
        String degradedReason,
        String model,
        String routingReason,
        long latencyMs,
        ChoiceSuggestion adoptLayer,
        DuplicateSuggestion duplicate,
        ScoreSuggestion quality,
        Map<String, DecisionAnswer> answers) {

    /**
     * @param value         机器值：{@code global|project|discard}（与 adopt API 的 target 同域）
     * @param label         中文短标签（未知取值回显原值）
     * @param probabilities 各选项概率（key 为机器值）
     */
    public record ChoiceSuggestion(String value, String label, Double confidence,
                                   Map<String, Double> probabilities) {
    }

    /**
     * @param duplicate   是否判为实质重复（noul 过半数）
     * @param probability 模型给的"是"的概率（0-1）
     * @param similar     召回到的相似条目——徽标说重复时，用户第一个问题就是"跟谁撞了"
     * @param note        召回降级/失败的说明（有值时这个结论要打问号）
     */
    public record DuplicateSuggestion(boolean duplicate, Double probability, Double confidence,
                                      List<SimilarEntry> similar, String note) {
    }

    /**
     * @param level 等级下标（0 起，与落库/训练口径一致）
     * @param label 中文短标签
     */
    public record ScoreSuggestion(Integer level, String label, Double confidence,
                                  Map<String, Double> probabilities) {
    }

    /** @param score 向量余弦（LIKE 降级时为 0，此时 only 名字有意义） */
    public record SimilarEntry(Long entryId, String entryName, Double score) {
    }

    /** 降级占位：有 at（说明分诊跑过了）但没有建议 */
    public static TriageView degraded(Instant at, String reason, long latencyMs) {
        return new TriageView(at, true, reason == null ? "" : reason, null, "", latencyMs,
                null, null, null, Map.of());
    }
}
