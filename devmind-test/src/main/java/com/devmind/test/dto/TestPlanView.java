package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TestPlanView(
        Long id,
        String projectId,
        String name,
        String description,
        Long environmentId,
        String failStrategy,
        Integer concurrency,
        Map<String, String> parameters,
        String requirementId,
        Boolean isTemplate,
        Integer planVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items) {

    public record Item(
            Long id,
            String refType,
            Long refId,
            Integer refVersion,
            Integer seq,
            String parallelGroup,
            Map<String, Object> overrides,
            Boolean enabled,
            String refName,
            String reviewStatus,
            Integer caseCount) {
    }
}
