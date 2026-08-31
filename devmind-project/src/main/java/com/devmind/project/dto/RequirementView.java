package com.devmind.project.dto;

import java.time.Instant;

/**
 * 需求视图（CAP-13 研发主线）：code = REQ-<seq> 项目内编号。
 */
public record RequirementView(
        String id,
        String projectId,
        Long seq,
        String code,
        String title,
        String description,
        String status,
        String ownerId,
        Long docId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
