package com.devmind.project.dto;

import java.time.Instant;

/**
 * 项目仓库视图（P0-4 多库模型）。
 */
public record RepoView(
        Long id,
        String projectId,
        String name,
        String path,
        String remoteUrl,
        String defaultBranch,
        String role,
        boolean primary,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt) {
}
