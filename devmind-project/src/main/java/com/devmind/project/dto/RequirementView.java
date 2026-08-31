package com.devmind.project.dto;

import java.time.Instant;

/**
 * 需求视图（P0-5）：code = REQ-<seq> 项目内编号。
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
        String branchSlug,
        Long docId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
