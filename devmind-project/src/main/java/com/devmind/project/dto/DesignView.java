package com.devmind.project.dto;

import java.time.Instant;

/**
 * 解决方案视图（CAP-13）：挂 Requirement 下，docId 指向 docs 方案文档。
 */
public record DesignView(
        String id,
        String projectId,
        String requirementId,
        Long docId,
        Integer version,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
