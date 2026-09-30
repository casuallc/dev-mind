package com.devmind.common.agent.runtime;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.SessionEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话用量账本：result 帧的 cost/usage 结构化提取（{@link CliEventParser}）
 * → 内核回调（{@link AbstractSessionRuntime} → {@link RuntimeListener#onTurnResult}）
 * → {@link TurnUsage} 归一化 的回归网。
 *
 * <p>WS 上行事件过 JSON 后数值类型会漂移（Long→Integer/Double），{@link TurnUsage#from}
 * 必须全容忍——否则 runner 报上来的用量静默不入账。</p>
 */
class TurnUsageLedgerTest {

    private final CliEventParser parser = new CliEventParser(JsonMapper.builder().build(),
            new RuntimeSettings(1000, 0, 100 * 1024, "acceptEdits", "", true));
    private long seq = 0;

    private SessionEvent parseOne(String line) {
        List<SessionEvent> out = parser.parse(() -> ++seq, line, "stdout");
        assertEquals(1, out.size());
        return out.get(0);
    }

    @Test
    void result帧的cost与usage结构化进payload() {
        SessionEvent ev = parseOne("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"done\",\"duration_ms\":2000,\"total_cost_usd\":0.0123,"
                + "\"usage\":{\"input_tokens\":1200,\"output_tokens\":340,"
                + "\"cache_read_input_tokens\":5600,\"cache_creation_input_tokens\":700}}");

        assertEquals("0.0123", ev.payload().get("cost"));
        assertEquals(2000L, ev.payload().get("durationMs"));
        assertEquals(1200L, ev.payload().get("inputTokens"));
        assertEquals(340L, ev.payload().get("outputTokens"));
        assertEquals(5600L, ev.payload().get("cacheReadTokens"));
        assertEquals(700L, ev.payload().get("cacheCreationTokens"));
        // 原始 usage 串保留（前端/排错可用）
        assertTrue(ev.payload().get("usage").toString().contains("input_tokens"));

        TurnUsage usage = TurnUsage.from(ev.payload());
        assertEquals(0.0123, usage.costUsd());
        assertEquals(1200L, usage.inputTokens());
        assertEquals(700L, usage.cacheCreationTokens());
    }

    @Test
    void result帧缺usage时不产token字段() {
        SessionEvent ev = parseOne("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"done\",\"duration_ms\":2000}");

        assertNull(ev.payload().get("inputTokens"));
        assertNull(ev.payload().get("usage"));
        // durationMs 在 → 非空，仍会计一个回合（MODEL 执行体正是这种形态）
        TurnUsage usage = TurnUsage.from(ev.payload());
        assertEquals(2000L, usage.durationMs());
        assertNull(usage.costUsd());
    }

    @Test
    void 全空payload的result不计回合() {
        assertTrue(TurnUsage.from(Map.of("isError", false, "subtype", "success")).isEmpty());
        assertTrue(TurnUsage.from(Map.of()).isEmpty());
    }

    @Test
    void JSON漂移的数值类型都能归一() {
        // WS 上行后：cost 可能是 Double 也可能是 String；token 可能是 Integer
        TurnUsage usage = TurnUsage.from(Map.of(
                "cost", 0.5d,
                "durationMs", 100,
                "inputTokens", 42,
                "outputTokens", "7"));
        assertEquals(0.5d, usage.costUsd());
        assertEquals(100L, usage.durationMs());
        assertEquals(42L, usage.inputTokens());
        assertEquals(7L, usage.outputTokens());
    }

    @Test
    void 内核收到带用量的result事件回调监听器() {
        List<TurnUsage> accounted = new ArrayList<>();
        RemoteSessionRuntime rt = runtime(accounted);

        rt.ingest(new AgentEventFrame("s1", "result", "done", "stdout", System.currentTimeMillis(),
                Map.of("isError", false, "cost", "0.0123", "inputTokens", 1200, "outputTokens", 340)));

        assertEquals(1, accounted.size());
        assertEquals(0.0123, accounted.get(0).costUsd());
        assertEquals(1200L, accounted.get(0).inputTokens());
    }

    @Test
    void 内核收到无用量的result事件不回调() {
        List<TurnUsage> accounted = new ArrayList<>();
        RemoteSessionRuntime rt = runtime(accounted);

        rt.ingest(new AgentEventFrame("s1", "result", "done", "stdout", System.currentTimeMillis(),
                Map.of("isError", false, "subtype", "success")));

        assertTrue(accounted.isEmpty());
    }

    private RemoteSessionRuntime runtime(List<TurnUsage> accounted) {
        RuntimeSettings settings = new RuntimeSettings(1000, 0, 100 * 1024, "acceptEdits", "", true);
        RuntimeEventSink sink = (sid, ev) -> { };
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
            }

            @Override
            public void onExit(String sessionId, int exitCode, boolean success, String summary) {
            }

            @Override
            public void onTurnResult(String sessionId, TurnUsage usage) {
                accounted.add(usage);
            }
        };
        return new RemoteSessionRuntime("s1", "node-1", null, sink, listener, settings);
    }
}
