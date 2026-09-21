package com.devmind.common.agent.runtime;

import com.devmind.common.agent.SessionEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-50：{@link CliEventParser} 对 claude {@code stream_event} 帧的过滤回归。
 *
 * <p>{@code --include-partial-messages} 打开后 stdout 会混入大量非正文帧——本类用真实帧形态
 * 钉死「只有主流正文增量放行」，因为放行错一帧就是整屏刷日志或气泡里混进乱码。
 * 帧 schema 取自 claude 2.1.278 实测。</p>
 */
class CliEventParserStreamEventTest {

    private final CliEventParser parser =
            new CliEventParser(new ObjectMapper(), RuntimeSettings.defaults());
    private final AtomicLong seq = new AtomicLong();

    private List<SessionEvent> parse(String line) {
        return parser.parse(seq::incrementAndGet, line, "stdout");
    }

    /** 增量帧外壳：{@code parent_tool_use_id} 为空表示主流（非子 agent）。 */
    private String frame(String eventJson) {
        return frame(eventJson, "null");
    }

    private String frame(String eventJson, String parentToolUseId) {
        return "{\"type\":\"stream_event\",\"event\":" + eventJson
                + ",\"parent_tool_use_id\":" + parentToolUseId
                + ",\"uuid\":\"u1\",\"session_id\":\"cli-1\"}";
    }

    // ---------------- 静默丢弃：非正文的流帧 ----------------

    @Test
    void 消息生命周期帧全部静默丢弃() {
        // 这些帧一回合各来一次，若降级成 log 就是每回合固定噪音
        for (String evType : List.of("message_start", "message_delta", "message_stop")) {
            assertTrue(parse(frame("{\"type\":\"" + evType + "\"}")).isEmpty(), evType);
        }
    }

    @Test
    void 内容块边界帧全部静默丢弃() {
        for (String evType : List.of("content_block_start", "content_block_stop")) {
            assertTrue(parse(frame("{\"type\":\"" + evType + "\",\"index\":0}")).isEmpty(), evType);
        }
    }

    @Test
    void ping帧静默丢弃() {
        assertTrue(parse(frame("{\"type\":\"ping\"}")).isEmpty());
    }

    @Test
    void 思考与工具入参增量不得当正文放行() {
        // thinking_delta 是思维链、input_json_delta 是工具入参，混进气泡就是乱码
        for (String deltaType : List.of("thinking_delta", "input_json_delta", "signature_delta", "citations_delta")) {
            String line = frame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\""
                    + deltaType + "\",\"text\":\"不该出现\"}}");
            assertTrue(parse(line).isEmpty(), deltaType);
        }
    }

    @Test
    void 未知帧类型静默丢弃而非降级为log() {
        // 命名空间级白名单：CLI 将来新增帧类型也自动静默，不能变成 log 刷屏
        assertTrue(parse(frame("{\"type\":\"prompt_suggestion\",\"text\":\"x\"}")).isEmpty());
        assertTrue(parse(frame("{}")).isEmpty());
    }

    // ---------------- 放行：主流正文增量 ----------------

    @Test
    void 主流正文增量产一条text_delta() {
        List<SessionEvent> events = parse(frame(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}"));

        assertEquals(1, events.size());
        SessionEvent ev = events.getFirst();
        assertEquals("text_delta", ev.type());
        assertEquals("你好", ev.content());
        assertEquals("stdout", ev.source());
        // 不带 payload：payload 会按 LONGTEXT 逐行落库，纯 stub 是白付行重
        assertTrue(ev.payload().isEmpty());
    }

    @Test
    void 空文本增量不产事件() {
        // 前端对空增量有守卫，但根本不该发出去——白占环形缓冲与 session_events 行
        assertTrue(parse(frame(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}"))
                .isEmpty());
    }

    @Test
    void 缺parent字段视为主流放行() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}}";
        assertEquals(1, parse(line).size());
    }

    // ---------------- 子 agent 流 ----------------

    @Test
    void 子agent增量被挡在主气泡之外() {
        // parent_tool_use_id 非空 = Task 子 agent 的流，混进来会把主流气泡搅乱
        String line = frame("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"子agent的正文\"}}", "\"tool-1\"");
        assertTrue(parse(line).isEmpty());
    }
}
