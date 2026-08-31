package com.devmind.deploy.dto;

import java.time.Instant;

/** 部署步骤视图（FR-02 逐步状态）。 */
public record StepView(
        Long id,
        int seq,
        String name,
        String type,
        String status,
        String detail,
        Instant startedAt,
        Instant finishedAt) {
}
