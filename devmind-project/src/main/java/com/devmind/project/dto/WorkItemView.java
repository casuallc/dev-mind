package com.devmind.project.dto;

import java.time.Instant;

/**
 * 工作单元视图（CAP-13）：code = WI-<seq> 项目内编号。
 */
public record WorkItemView(
        String id,
        String projectId,
        String requirementId,
        String designId,
        Long seq,
        String code,
        String type,
        String title,
        String spec,
        String status,
        String ownerId,
        String branchSlug,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
