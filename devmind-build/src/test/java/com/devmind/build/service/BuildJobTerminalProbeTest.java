package com.devmind.build.service;

import com.devmind.build.dto.BuildView;
import com.devmind.common.agent.plan.JobReport;
import com.devmind.common.agent.plan.JobState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BuildJobTerminalProbe#terminalReport(String)}（CAP-76 FR-37）单测：
 * 成功/失败终态的耗时与摘要、异常与非法 id 降级 null。仓库无 mockito，直接匿名子类覆写 get。
 */
class BuildJobTerminalProbeTest {

    private static BuildView view(String status, Instant startedAt, Instant finishedAt, String errorSummary) {
        return new BuildView(42L, "p1", null, "abc123", "main", "local", null,
                status, 0, errorSummary, startedAt, finishedAt, startedAt, null);
    }

    private static BuildService stub(BuildView view) {
        return new BuildService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Duration.ofHours(2)) {
            @Override
            public BuildView get(Long id) {
                if (view == null) {
                    throw new IllegalStateException("构建记录不存在: " + id);
                }
                return view;
            }
        };
    }

    @Test
    void successReportCarriesDurationAndBuildId() {
        Instant started = Instant.parse("2026-11-02T10:00:00Z");
        Instant finished = started.plusMillis(65_000);
        BuildJobTerminalProbe probe = new BuildJobTerminalProbe(stub(view("SUCCESS", started, finished, null)));

        JobReport report = probe.terminalReport("42");

        assertEquals(JobState.SUCCEEDED, report.state());
        assertEquals(65_000L, report.durationMs());
        assertEquals("构建成功", report.summary());
        assertEquals(42L, report.metrics().get("buildId"));
    }

    @Test
    void failedReportUsesErrorSummary() {
        Instant started = Instant.parse("2026-11-02T10:00:00Z");
        BuildJobTerminalProbe probe = new BuildJobTerminalProbe(
                stub(view("FAILED", started, started.plusMillis(3_000), "编译失败：找不到符号")));

        JobReport report = probe.terminalReport("42");

        assertEquals(JobState.FAILED, report.state());
        assertEquals(3_000L, report.durationMs());
        assertEquals("编译失败：找不到符号", report.summary());
        assertEquals(42L, report.metrics().get("buildId"));
    }

    @Test
    void runningBuildHasNoDurationAndMapsRunning() {
        Instant started = Instant.parse("2026-11-02T10:00:00Z");
        BuildJobTerminalProbe probe = new BuildJobTerminalProbe(stub(view("RUNNING", started, null, null)));

        JobReport report = probe.terminalReport("42");

        assertEquals(JobState.RUNNING, report.state());
        assertNull(report.durationMs());
    }

    @Test
    void invalidOrMissingIdDegradesToNull() {
        BuildJobTerminalProbe probe = new BuildJobTerminalProbe(stub(view("SUCCESS",
                Instant.parse("2026-11-02T10:00:00Z"), Instant.parse("2026-11-02T10:01:00Z"), null)));

        assertNull(probe.terminalReport("not-a-number"), "非法 id 降级 null");
        assertNull(probe.terminalReport(null), "空 id 降级 null");
    }

    @Test
    void missingBuildDegradesToNull() {
        BuildJobTerminalProbe probe = new BuildJobTerminalProbe(stub(null));

        assertNull(probe.terminalReport("42"), "读取异常时降级 null（推送按无报告渲染）");
    }
}
