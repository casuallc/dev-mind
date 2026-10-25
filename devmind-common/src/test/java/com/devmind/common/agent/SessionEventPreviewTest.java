package com.devmind.common.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件 payload 预览截断：只动 tool_use.toolInput / tool_result.content，截断必带 previewTruncated
 * 标记（前端凭它决定展开时按 seq 补拉全文）；其余事件类型与未超长字段原样透传（同一引用）。
 */
class SessionEventPreviewTest {

    private static SessionEvent ev(String type, String content, Map<String, Object> payload) {
        return new SessionEvent(1L, type, content, "stdout", 0L, payload);
    }

    @Test
    void toolResult正文超长截断并打标记() {
        SessionEvent tr = ev("tool_result", "x".repeat(100), Map.of("isError", false));
        SessionEvent out = SessionEventPreview.truncate(List.of(tr), 8).get(0);

        assertEquals("xxxxxxxx…", out.content());
        assertEquals(Boolean.TRUE, out.payload().get(SessionEventPreview.TRUNCATED_KEY));
        assertEquals(false, out.payload().get("isError"), "既有 payload 键必须保留");
    }

    @Test
    void toolUse的toolInput超长截断并打标记() {
        SessionEvent tu = ev("tool_use", null, Map.of("name", "Bash", "toolInput", "y".repeat(50)));
        SessionEvent out = SessionEventPreview.truncate(List.of(tu), 8).get(0);

        assertEquals("yyyyyyyy…", out.payload().get("toolInput"));
        assertEquals(Boolean.TRUE, out.payload().get(SessionEventPreview.TRUNCATED_KEY));
        assertEquals("Bash", out.payload().get("name"));
    }

    @Test
    void 未超长与其余类型原样透传() {
        SessionEvent shortTool = ev("tool_result", "short", Map.of());
        SessionEvent assistant = ev("assistant", "a".repeat(500), Map.of());
        List<SessionEvent> out = SessionEventPreview.truncate(List.of(shortTool, assistant), 8);

        assertSame(shortTool, out.get(0), "未超长不该换对象（下游按引用判变）");
        assertSame(assistant, out.get(1), "assistant 正文渲染要用，永不截断");
    }

    @Test
    void max非正数时整列透传() {
        SessionEvent tr = ev("tool_result", "x".repeat(100), Map.of());
        List<SessionEvent> src = List.of(tr);

        assertSame(src, SessionEventPreview.truncate(src, 0), "payloadMax<=0 是兼容旧调用方的关闭态");
    }

    @Test
    void payload为空时截断仍只动content() {
        SessionEvent tr = new SessionEvent(1L, "tool_result", "x".repeat(20), "stdout", 0L, null);
        SessionEvent out = SessionEventPreview.truncate(List.of(tr), 4).get(0);

        assertEquals("xxxx…", out.content());
        assertEquals(Boolean.TRUE, out.payload().get(SessionEventPreview.TRUNCATED_KEY));
        assertNull(SessionEventPreview.truncate(List.of(tr), 100).get(0).payload(), "未超长时 null payload 不换位");
    }
}
