package com.devmind.common.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAP-55 FR-03 决策结果：模型答案 + 本次路由信息 + 降级标记，一次决策的完整交代。
 *
 * <p><b>降级是正常返回值，不是异常</b>（FR-06）：没配端点、边车连不上、应答解析失败，
 * 一律返回 {@link #degraded} 结果，由调用方决定"继续走人工路径"还是"提示重试"。
 * 这样消费方（知识库分诊）的业务代码里没有 try/catch，降级链也只有一条出口。</p>
 *
 * <p>{@link #degraded()} 为真时 {@link #answers()} 必为空 map（不会是 null），
 * 且 {@link #degradedReason()} 非空——UI 就是靠它把「分诊」按钮置灰并说明原因（FR-07）。
 * 反过来 {@code degraded=false} 时 {@code answers} 也可能为空：题都发出去了但模型一道都没答，
 * 这属于"边车在、模型没答"，与降级是两件事，调用方应各自处理。</p>
 *
 * @param answers       逐题答案（题 id → 答案）；键序保留边车返回顺序，缺失题不补 null
 * @param routingModel  边车选中的 checkpoint 别名（可空 = 边车未报）
 * @param routingReason 选择该 checkpoint 的原因（可空；多半是"语言/脚本不匹配"，
 *                      排障时它是第一手证据，故与模型名一并带出）
 * @param degraded      是否降级（未配置端点 / 调用失败 / 应答不可解析）
 * @param degradedReason 降级原因（可空串；已脱敏，可直接进日志与 UI）
 * @param latencyMs     本次决策耗时（含重试与降级等待，单位毫秒）
 */
public record DecisionResult(
        Map<String, DecisionAnswer> answers,
        String routingModel,
        String routingReason,
        boolean degraded,
        String degradedReason,
        long latencyMs) {

    public DecisionResult {
        // 键序即边车返回顺序：FR-07 的「查看依据」抽屉按原序展示，UI 不该自己再排一遍
        answers = answers == null || answers.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        if (degradedReason == null) {
            degradedReason = "";
        }
    }

    /** 成功结果：模型答了（答了几道、对不对由调用方自己看 {@link #answers()}） */
    public static DecisionResult ok(Map<String, DecisionAnswer> answers, String routingModel,
                                    String routingReason, long latencyMs) {
        return new DecisionResult(answers, routingModel, routingReason, false, "", latencyMs);
    }

    /**
     * 降级结果：没答案、只有原因。{@code reason} 会经 devmind-model 的脱敏口径处理，
     * 可以直接落库/回显。
     */
    public static DecisionResult degraded(String reason, long latencyMs) {
        return new DecisionResult(Map.of(), null, null, true, reason, latencyMs);
    }

    /** 取某题答案；没这题/模型没答 → null（不抛，调用方判空即可） */
    public DecisionAnswer answer(String questionId) {
        return answers.get(questionId);
    }

    /** 这道题模型答了没（分诊三题里少一题时，UI 要能说清缺的是哪题） */
    public boolean hasAnswer(String questionId) {
        return answers.containsKey(questionId);
    }

    /** 降级原因单行摘要，给日志用（不喂 UI，UI 用 {@link #degradedReason()} 原文） */
    public String summary() {
        return degraded
                ? "降级（" + degradedReason + "，耗时 " + latencyMs + " ms）"
                : "成功（" + answers.size() + " 题，checkpoint=" + (routingModel == null ? "未报" : routingModel)
                        + "，耗时 " + latencyMs + " ms）";
    }
}
