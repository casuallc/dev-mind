package com.devmind.project.dto;

import java.time.Instant;

/**
 * 项目仓库视图（P0-4 多库模型）。
 * CAP-23：sourceType=CLONE 时携带克隆状态机字段（cloneStatus/cloneError/clonedAt）与 integrationId。
 */
public record RepoView(
        Long id,
        String projectId,
        String name,
        String path,
        String sourceType,
        String remoteUrl,
        Long integrationId,
        String defaultBranch,
        String role,
        boolean primary,
        int sortOrder,
        String cloneStatus,
        String cloneError,
        Instant clonedAt,
        Instant createdAt,
        Instant updatedAt) {
}
