package com.devmind.test.dto;

import java.util.List;
import java.util.Map;

/** CAP-104 M2 基线对比：查询视图，不改写任何历史运行。 */
public record BaselineComparison(
        Long baselineId,
        Long planRunId,
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary,
        List<CaseDiff> newFailures,
        List<CaseDiff> fixed,
        List<CaseDiff> persistentFailures,
        List<CaseDiff> newCases,
        List<CaseDiff> removedCases,
        double passRateDelta,
        long durationDeltaMs) {

    public record CaseDiff(
            Long caseId,
            String caseKey,
            String name,
            String baselineStatus,
            String currentStatus,
            Integer baselineVersion,
            Integer currentVersion,
            Long currentRunId,
            String error) {
    }
}
