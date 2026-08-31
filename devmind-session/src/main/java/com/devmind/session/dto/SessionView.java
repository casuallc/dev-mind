package com.devmind.session.dto;

import com.devmind.session.model.SessionState;

import java.time.Instant;

/**
 * 会话视图（看板/详情）。status 为实时状态（内存运行时优先）。
 */
public record SessionView(
        String id,
        String projectId,
        String requirementId,
        String taskSpec,
        String status,
        SessionState state,
        String worktreePath,
        Long pid,
        String model,
        String summary,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {
}
