package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TestBaselineView(
        Long id,
        String projectId,
        String name,
        String productVersion,
        Long planId,
        Long planRunId,
        Map<String, Object> summary,
        Integer caseCount,
        List<Map<String, Object>> cases,
        String createdBy,
        Instant createdAt) {
}
