package com.devmind.common.agent.tool;

import java.util.Map;

/**
 * 一次工具调用（CAP-76 FR-02）：{@code callId} 对应模型的 {@code tool_call_id}；
 * {@code args} 是模型给出的原始参数（校验前）。
 */
public record ToolCall(String callId, String name, Map<String, Object> args) {
}
