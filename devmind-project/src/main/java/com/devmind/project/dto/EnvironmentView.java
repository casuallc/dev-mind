package com.devmind.project.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EnvironmentView(
        Long id,
        String projectId,
        String name,
        String description,
        List<Long> serverIds,
        Map<String, String> variables,
        List<String> secrets,
        Instant createdAt,
        Instant updatedAt) {
}
