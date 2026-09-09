package com.devmind.project.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EnvironmentView(
        Long id,
        String projectId,
        String name,
        String description,
        /** 目标 runner 节点 id 列表（CAP-36） */
        List<String> nodeIds,
        Map<String, String> variables,
        List<String> secrets,
        Instant createdAt,
        Instant updatedAt) {
}
