package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** CAP-104 FR-21 统计视图：趋势/Top 失败/flaky/耗时/环境对比，口径在服务层统一。 */
public record TestStatsView(
        Instant from,
        Instant to,
        String granularity,
        Map<String, Object> overview,
        List<TrendPoint> trend,
        List<FailureStat> topFailures,
        List<FlakyStat> flaky,
        Map<String, Object> duration,
        List<EnvironmentStat> environments) {

    public record TrendPoint(
            String bucket,
            int runs,
            int total,
            int passed,
            int failed,
            int errors,
            int blocked,
            int skipped,
            Double passRate) {
    }

    public record FailureStat(
            Long caseId,
            String caseKey,
            String name,
            int failures,
            int errors,
            String lastStatus,
            Long lastRunId,
            Instant lastAt,
            String lastError,
            String failureCategory) {
    }

    public record FlakyStat(
            Long caseId,
            String caseKey,
            String name,
            int samples,
            int passes,
            int failures,
            int blocked,
            Double failRate,
            boolean retryPass,
            String lastStatus,
            Instant lastAt) {
    }

    public record EnvironmentStat(
            Long environmentId,
            String name,
            int runs,
            int total,
            int passed,
            int failed,
            int errors,
            int blocked,
            int skipped,
            Double passRate,
            Long p95Ms) {
    }
}
