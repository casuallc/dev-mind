package com.devmind.worklog.dto;

import com.devmind.worklog.model.GitRepositoryEntity;

import java.time.Instant;

/** 全局仓库视图；subscribed 为当前用户是否已勾选（列表时填充）。 */
public record RepoView(Long id, String name, String localPath, String remoteUrl,
                       String defaultBranch, String status, String createdBy,
                       Boolean subscribed, Instant createdAt, Instant updatedAt) {

    public static RepoView of(GitRepositoryEntity e, boolean subscribed) {
        return new RepoView(e.getId(), e.getName(), e.getLocalPath(), e.getRemoteUrl(),
                e.getDefaultBranch(), e.getStatus(), e.getCreatedBy(),
                subscribed, e.getCreatedAt(), e.getUpdatedAt());
    }
}
