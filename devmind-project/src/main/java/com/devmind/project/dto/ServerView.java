package com.devmind.project.dto;

import java.time.Instant;
import java.util.List;

public record ServerView(
        Long id,
        String projectId,
        String name,
        String env,
        String accessType,
        String accessConfig,
        List<String> capabilities,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
