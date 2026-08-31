package com.devmind.project.dto;

import java.time.Instant;

/**
 * 任务视图（Task 主线）：code = TASK-<seq> 项目内编号。
 */
public record TaskView(
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
