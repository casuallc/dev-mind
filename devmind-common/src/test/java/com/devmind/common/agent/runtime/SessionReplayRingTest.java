package com.devmind.common.agent.runtime;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.SessionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-50：{@code text_delta} 不进环形缓冲的回归。
 *
 * <p>环形缓冲是会话 WS snapshot 回放的唯一来源，而前端只在该会话已不活跃时才走 REST 拉全量
 * 历史——会话进行中刷新页面时它就是唯一历史来源。若不排除增量，一条长回答几百条增量会把前面
 * 的话题、工具卡片、历史提问整片挤出回放窗口。</p>
 */
class SessionReplayRingTest {

    private final List<SessionEvent> saved = new ArrayList<>();
    private final List<SessionEvent> pushed = new ArrayList<>();

    private RemoteSessionRuntime runtime(int ringBuffer) {
        RuntimeSettings settings = new RuntimeSettings(ringBuffer, 0, 100 * 1024, "acceptEdits", "", true);
        RuntimeEventSink sink = (sid, ev) -> saved.add(ev);
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
            }

            @Override
            public void onExit(String sessionId, int exitCode, boolean success, String summary) {
            }
        };
        return new RemoteSessionRuntime("s1", "node-1", null, sink, listener, settings);
    }

    private void feed(RemoteSessionRuntime rt, String type, String content) {
        rt.ingest(new AgentEventFrame("s1", type, content, "stdout", System.currentTimeMillis(), Map.of()));
    }

    private List<String> types(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::type).toList();
    }

    @Test
    void 增量不进回放但广播与落库都不受影响() {
        RemoteSessionRuntime rt = runtime(1000);
        List<SessionEvent> snapshot = rt.subscribe(pushed::add);
        assertEquals(List.of(), types(snapshot));

        feed(rt, "assistant", "上一轮回答");
        feed(rt, "text_delta", "你");
        feed(rt, "text_delta", "好");
        feed(rt, "tool_use", "Bash");
        feed(rt, "assistant", "你好");

        // 实时推送照旧：打字机效果靠这条路径
        assertEquals(List.of("assistant", "text_delta", "text_delta", "tool_use", "assistant"), types(pushed));
        // 落库照旧：会话被中断时增量是这段正文的唯一痕迹
        assertEquals(5, saved.size());
        // 回放里没有增量：刷新后靠全量 assistant 成形，不出现碎片
        assertEquals(List.of("assistant", "tool_use", "assistant"), types(rt.replay()));
    }

    @Test
    void 增量不挤占回放窗口() {
        // 缓冲仅 4：不排除增量的话，6 条增量会把前面那条 assistant 挤光
        RemoteSessionRuntime rt = runtime(4);
        feed(rt, "assistant", "提问前的上下文");
        for (int i = 0; i < 6; i++) {
            feed(rt, "text_delta", "片" + i);
        }
        feed(rt, "tool_use", "Bash");
        feed(rt, "assistant", "完整回答");

        assertEquals(List.of("assistant", "tool_use", "assistant"), types(rt.replay()));
    }

    @Test
    void 非增量事件仍按环形缓冲上限淘汰() {
        RemoteSessionRuntime rt = runtime(2);
        feed(rt, "log", "1");
        feed(rt, "log", "2");
        feed(rt, "log", "3");

        assertEquals(List.of("2", "3"), rt.replay().stream().map(SessionEvent::content).toList());
    }
}
