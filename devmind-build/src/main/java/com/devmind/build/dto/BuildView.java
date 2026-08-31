package com.devmind.build.dto;

import java.time.Instant;

public record BuildView(
        Long id,
        String projectId,
        String workItemId,
        String commit,
        String branch,
        String executor,
        String artifactRef,
        String status,
        Integer exitCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {
}
