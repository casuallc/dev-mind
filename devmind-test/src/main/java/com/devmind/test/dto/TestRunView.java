package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

public record TestRunView(
        Long id,
        String projectId,
        String workItemId,
        List<Long> suiteIds,
        Long deploymentId,
        String agentNodeId,
        Long environmentId,
        String baseUrl,
        String status,
        RunSummary summary,
        Long reportDocId,
        String errorSummary,
        String triggeredBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        List<CaseResultView> results) {
}
