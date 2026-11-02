package com.devmind.session.service;

import com.devmind.common.agent.plan.JobReport;
import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.session.dto.SessionView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SessionJobTerminalProbe#terminalReport(String)}（CAP-76 FR-37）单测：
 * token 用量透传、sessionId 前 8 位、异常降级 null。仓库无 mockito，直接匿名子类覆写 get。
 */
class SessionJobTerminalProbeTest {

    private static SessionView view(String id, SessionState state, String summary,
                                    Instant createdAt, Instant finishedAt,
                                    Long inputTokens, Long outputTokens,
                                    Long cacheReadTokens, Long cacheCreationTokens) {
        return new SessionView(id, "p1", null, null, "任务", state == null ? null : state.name(),
                state, null, null, "fake", summary, "node-1", null, "tester", null,
                createdAt, finishedAt, finishedAt, null,
                inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, 3);
    }

    private static SessionManagerService stub(SessionView view) {
        return new SessionManagerService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null) {
            @Override
            public SessionView get(String id) {
                if (view == null) {
                    throw new IllegalStateException("会话不存在: " + id);
                }
                return view;
            }
        };
    }

    @Test
    void doneReportCarriesTokensAndShortSessionId() {
        Instant created = Instant.parse("2026-11-02T10:00:00Z");
        Instant finished = created.plusMillis(90_000);
        SessionJobTerminalProbe probe = new SessionJobTerminalProbe(stub(view(
                "abcdef1234567890", SessionState.DONE, "已生成 3 个文件", created, finished,
                1200L, 340L, 5000L, 60L)));

        JobReport report = probe.terminalReport("abcdef1234567890");

        assertEquals(JobState.SUCCEEDED, report.state());
        assertEquals(90_000L, report.durationMs());
        assertEquals("已生成 3 个文件", report.summary());
        assertEquals("abcdef12", report.metrics().get("sessionId"), "sessionId 只放前 8 位");
        assertEquals(1200L, report.metrics().get("inputTokens"));
        assertEquals(340L, report.metrics().get("outputTokens"));
        assertEquals(5000L, report.metrics().get("cacheReadTokens"));
        assertEquals(60L, report.metrics().get("cacheCreationTokens"));
    }

    @Test
    void nullTokenMetricsAreOmitted() {
        Instant created = Instant.parse("2026-11-02T10:00:00Z");
        SessionJobTerminalProbe probe = new SessionJobTerminalProbe(stub(view(
                "sid-1", SessionState.DONE, "ok", created, created.plusMillis(1000),
                10L, null, null, null)));

        JobReport report = probe.terminalReport("sid-1");

        assertEquals(10L, report.metrics().get("inputTokens"));
        assertFalse(report.metrics().containsKey("outputTokens"), "null 指标不入 metrics");
        assertFalse(report.metrics().containsKey("cacheReadTokens"));
        assertFalse(report.metrics().containsKey("cacheCreationTokens"));
    }

    @Test
    void failedAndTerminatedMapToFailed() {
        Instant created = Instant.parse("2026-11-02T10:00:00Z");
        SessionJobTerminalProbe failed = new SessionJobTerminalProbe(stub(view(
                "sid-f", SessionState.FAILED, "执行失败：磁盘满", created, created.plusMillis(500),
                null, null, null, null)));
        SessionJobTerminalProbe terminated = new SessionJobTerminalProbe(stub(view(
                "sid-t", SessionState.TERMINATED, "已终止", created, created.plusMillis(500),
                null, null, null, null)));

        assertEquals(JobState.FAILED, failed.terminalReport("sid-f").state());
        assertEquals("执行失败：磁盘满", failed.terminalReport("sid-f").summary());
        assertEquals(JobState.FAILED, terminated.terminalReport("sid-t").state());
    }

    @Test
    void blankOrMissingSessionDegradesToNull() {
        SessionJobTerminalProbe missing = new SessionJobTerminalProbe(stub(null));
        SessionJobTerminalProbe present = new SessionJobTerminalProbe(stub(view(
                "sid-1", SessionState.DONE, "ok",
                Instant.parse("2026-11-02T10:00:00Z"), Instant.parse("2026-11-02T10:00:01Z"),
                null, null, null, null)));

        assertNull(missing.terminalReport("sid-x"), "读取异常时降级 null");
        assertNull(present.terminalReport("  "), "空白 id 降级 null");
        assertNull(present.terminalReport(null), "空 id 降级 null");
    }
}
