package com.devmind.project.dto;

import java.time.Instant;
import java.util.List;

public record ProjectView(
        String id,
        String name,
        String path,
        String defaultBranch,
        List<String> tags,
        String description,
        String status,
        String apiDocSource,
        Boolean autoRegressionOnDeploy,
        String contextSummary,
        Instant summaryGeneratedAt,
        String ownerId,
        Instant createdAt,
        Instant updatedAt) {
}
