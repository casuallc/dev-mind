package com.devmind.common.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行结果（CAP-76 FR-02）。
 *
 * @param success          是否成功
 * @param errorCode        稳定错误码（见 {@link #INVALID_ARGUMENTS} 等常量；成功为 null）
 * @param errorMessage     给用户/模型看的脱敏原因（禁堆栈、禁内部路径）
 * @param summary          回给模型的紧凑结果（网关会按 max-tool-result-chars 截断）
 * @param displayMarkdown  回给用户的展示文本（空则用 summary）
 * @param data             结构化结果（不直接给模型；供卡片/链接生成）
 * @param sensitive        true 时不落库原文，只记成功/失败与摘要哈希
 */
public record ToolResult(
        boolean success,
        String errorCode,
        String errorMessage,
        String summary,
        String displayMarkdown,
        Map<String, Object> data,
        boolean sensitive) {

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String IDENTITY_REQUIRED = "IDENTITY_REQUIRED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String PROJECT_REQUIRED = "PROJECT_REQUIRED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE";
    public static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    public static final String TIMEOUT = "TIMEOUT";

    public ToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult ok(String summary) {
        return new ToolResult(true, null, null, summary, null, Map.of(), false);
    }

    public static ToolResult ok(String summary, String displayMarkdown, Map<String, Object> data) {
        return new ToolResult(true, null, null, summary, displayMarkdown, data, false);
    }

    public static ToolResult fail(String errorCode, String errorMessage) {
        return new ToolResult(false, errorCode, errorMessage, null, null, Map.of(), false);
    }

    /** 成功但结果含敏感信息：审计只留摘要哈希。 */
    public ToolResult asSensitive() {
        return new ToolResult(success, errorCode, errorMessage, summary, displayMarkdown, data, true);
    }

    /** 便于工具拼装 data 的小助手（保持插入顺序，便于日志阅读）。 */
    public static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
