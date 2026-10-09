package com.devmind.common.agent.runtime;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.SessionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AbstractSessionRuntime#restoreFromHistory} 回归：重建运行时（服务端重启 reattach /
 * resume 恢复）后回填 DB 历史——seq 续接最大值不重编（防与存量撞号），环形缓冲补最近一段
 * 非 text_delta 事件（活动态会话 WS snapshot 的唯一历史来源）。
 */
class RuntimeHistoryRestoreTest {

    private RemoteSessionRuntime runtime(int ringBuffer) {
        RuntimeSettings settings = new RuntimeSettings(ringBuffer, 0, 100 * 1024, "acceptEdits", "", true);
        RuntimeEventSink sink = (sid, ev) -> { };
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

    private SessionEvent ev(long seq, String type, String content) {
        return SessionEvent.of(seq, type, content, "stdout", System.currentTimeMillis(), Map.of());
    }

    @Test
    void 回填后seq续接最大值_新事件不与存量撞号() {
        RemoteSessionRuntime rt = runtime(1000);
        rt.restoreFromHistory(List.of(ev(1, "system", "init"), ev(2, "assistant", "回答")));

        rt.ingest(new AgentEventFrame("s1", "assistant", "新事件", "stdout",
                System.currentTimeMillis(), Map.of()));

        List<SessionEvent> replay = rt.replay();
        assertEquals(3, replay.size());
        assertEquals(3, replay.get(2).seq()); // 1、2 之后续 3，不是从 1 重编
    }

    @Test
    void 回填跳过增量且受回放窗口容量约束() {
        RemoteSessionRuntime rt = runtime(2);
        rt.restoreFromHistory(List.of(
                ev(1, "assistant", "旧1"),
                ev(2, "text_delta", "片"),
                ev(3, "assistant", "旧2"),
                ev(4, "tool_use", "Bash")));

        // text_delta 不进回放；容量 2 只留最近两条非增量；seq 仍取全量最大值
        assertEquals(List.of("旧2", "Bash"),
                rt.replay().stream().map(SessionEvent::content).toList());
        assertEquals(4, rt.currentSeq());
    }

    @Test
    void 空历史与null都不改变运行时() {
        RemoteSessionRuntime rt = runtime(1000);
        rt.restoreFromHistory(null);
        rt.restoreFromHistory(new ArrayList<>());
        assertEquals(0, rt.currentSeq());
        assertEquals(List.of(), rt.replay());
    }
}
