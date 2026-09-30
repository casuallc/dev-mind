package com.devmind.common.agent.runtime;

import java.util.Map;

/**
 * 单回合用量（{@code result} 事件 payload 的结构化提取）。
 *
 * <p>来源两种：claude CLI 的 result 帧（cost/tokens 全有，由 {@code CliEventParser} 结构化进 payload）、
 * 模型执行体 CAP-49（只有 durationMs）。各字段可空 = 该执行体不上报此项；{@link #isEmpty()} 为 true
 * 时内核不通知监听器（不入账、不计回合）。</p>
 *
 * <p>payload 里 cost 是字符串（{@code CliEventParser} 从 {@code total_cost_usd} asText 而来，保精度），
 * 数值字段则可能是 Integer/Long/Double（Jackson 反序列化 JSON 的实际类型），{@link #from} 统一归一。</p>
 */
public record TurnUsage(Double costUsd, Long durationMs, Long inputTokens, Long outputTokens,
                        Long cacheReadTokens, Long cacheCreationTokens) {

    public static TurnUsage from(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new TurnUsage(null, null, null, null, null, null);
        }
        return new TurnUsage(
                asDouble(payload.get("cost")),
                asLong(payload.get("durationMs")),
                asLong(payload.get("inputTokens")),
                asLong(payload.get("outputTokens")),
                asLong(payload.get("cacheReadTokens")),
                asLong(payload.get("cacheCreationTokens")));
    }

    /** 全空 = 该 result 不带任何用量信息（如老 runner），不通知监听器。 */
    public boolean isEmpty() {
        return costUsd == null && durationMs == null && inputTokens == null && outputTokens == null
                && cacheReadTokens == null && cacheCreationTokens == null;
    }

    private static Double asDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
