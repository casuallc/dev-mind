package com.devmind.project.dto;

import java.time.Instant;

public record ContextSummaryView(
        String projectId,
        String summary,
        Instant generatedAt) {
}
