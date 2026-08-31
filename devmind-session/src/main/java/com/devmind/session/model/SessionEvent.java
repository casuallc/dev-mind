package com.devmind.session.model;

import java.util.Map;

/**
 * 统一事件模型（环形缓冲/WS/落库共用）。
 *
 * @param seq       会话内递增序号
 * @param type      事件类型：system/assistant/user/tool_use/tool_result/text_delta/
 *                  permission_request/permission_result/result/error/state/log
 * @param content   文本内容（assistant 回复、工具输出、错误信息等）
 * @param source    stdout / stderr / system
 * @param timestamp epoch millis
 * @param payload   结构化负载（permission_request 的 toolName/input/requestId，result 的 is_error 等）
 */
public record SessionEvent(long seq, String type, String content, String source, long timestamp, Map<String, Object> payload) {

    public static SessionEvent of(long seq, String type, String content, String source) {
        return new SessionEvent(seq, type, content, source, System.currentTimeMillis(), Map.of());
    }

    public static SessionEvent of(long seq, String type, String content, String source, Map<String, Object> payload) {
        return new SessionEvent(seq, type, content, source, System.currentTimeMillis(), payload);
    }

    public static SessionEvent of(long seq, String type, String content, String source,
                                  long timestamp, Map<String, Object> payload) {
        return new SessionEvent(seq, type, content, source, timestamp, payload);
    }

    public boolean isState() {
        return "state".equals(type);
    }
}
