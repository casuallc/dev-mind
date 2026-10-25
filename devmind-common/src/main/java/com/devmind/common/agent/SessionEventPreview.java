package com.devmind.common.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件历史的 payload 预览截断（sessions/chats 共用）。
 *
 * <p>tool_use 的 toolInput 与 tool_result 的 content 单条可到 100KB（整文件读/写、长命令输出），
 * 历史首屏一页 200 条就是几十 MB——而渲染层工具卡片默认折叠、只看摘要行，全文只在用户展开
 * 卡片时才需要（按 seq 走单条全文端点补取）。截断后在 payload 置 {@link #TRUNCATED_KEY}=true，
 * 前端凭它决定展开时是否补拉。</p>
 */
public final class SessionEventPreview {

    public static final String TRUNCATED_KEY = "previewTruncated";

    private SessionEventPreview() {
    }

    /** max<=0 不截断（兼容旧调用方）。 */
    public static List<SessionEvent> truncate(List<SessionEvent> events, int max) {
        if (max <= 0) {
            return events;
        }
        List<SessionEvent> out = new ArrayList<>(events.size());
        for (SessionEvent ev : events) {
            out.add(truncateOne(ev, max));
        }
        return out;
    }

    private static SessionEvent truncateOne(SessionEvent ev, int max) {
        return switch (ev.type()) {
            case "tool_use" -> truncatePayloadField(ev, "toolInput", max);
            case "tool_result" -> truncateContent(ev, max);
            default -> ev;
        };
    }

    private static SessionEvent truncateContent(SessionEvent ev, int max) {
        String content = ev.content();
        if (content == null || content.length() <= max) {
            return ev;
        }
        return new SessionEvent(ev.seq(), ev.type(), content.substring(0, max) + "…",
                ev.source(), ev.timestamp(), withMarker(ev.payload()));
    }

    private static SessionEvent truncatePayloadField(SessionEvent ev, String field, int max) {
        Object v = ev.payload() == null ? null : ev.payload().get(field);
        if (!(v instanceof String s) || s.length() <= max) {
            return ev;
        }
        Map<String, Object> payload = withMarker(ev.payload());
        payload.put(field, s.substring(0, max) + "…");
        return new SessionEvent(ev.seq(), ev.type(), ev.content(), ev.source(), ev.timestamp(), payload);
    }

    private static Map<String, Object> withMarker(Map<String, Object> payload) {
        Map<String, Object> copy = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        copy.put(TRUNCATED_KEY, true);
        return copy;
    }
}
