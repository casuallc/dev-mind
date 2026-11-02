package com.devmind.common.agent.plan;

import java.util.Map;

/**
 * 作业终态报告（CAP-76 FR-37，计划推送用）：终态、耗时、产出摘要与关键指标
 * （如构建 id、会话 token 用量）。{@link JobTerminalProbe#terminalReport(String)}
 * 返回 null = 无额外信息，推送降级为只报终态。
 *
 * @param state      作业终态
 * @param durationMs 作业耗时（起止时间差；未知为 null）
 * @param summary    产出/失败摘要（可空；调用方负责脱敏与截断）
 * @param metrics    关键指标（buildId、sessionId 前 8 位、token 用量等；禁 null 值）
 */
public record JobReport(JobState state, Long durationMs, String summary, Map<String, Object> metrics) {

    public JobReport {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
