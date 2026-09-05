package com.devmind.project.dto;

import com.devmind.project.model.GitRepositoryEntity;

import java.time.Instant;
import java.util.List;

/** CAP-29 全局仓库视图。branches 换行分隔存储，对外拆成列表。 */
public record GitRepoView(Long id, String name, String localPath, String remoteUrl,
                          String defaultBranch, String sourceType, Long integrationId,
                          String cloneStatus, String cloneError, List<String> branches,
                          Instant lastFetchAt, String lastFetchError,
                          String status, String createdBy, Instant createdAt, Instant updatedAt) {

    public static GitRepoView of(GitRepositoryEntity e) {
        List<String> branchList = e.getBranches() == null || e.getBranches().isBlank()
                ? List.of()
                : e.getBranches().lines().filter(s -> !s.isBlank()).toList();
        return new GitRepoView(e.getId(), e.getName(), e.getLocalPath(), e.getRemoteUrl(),
                e.getDefaultBranch(), e.getSourceType(), e.getIntegrationId(),
                e.getCloneStatus(), e.getCloneError(), branchList,
                e.getLastFetchAt(), e.getLastFetchError(),
                e.getStatus(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
