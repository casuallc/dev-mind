package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 全局仓库登记/更新请求（CAP-28 FR-01，写操作仅 ADMIN）。 */
public record RepoRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 1024) String localPath,
        @Size(max = 512) String remoteUrl,
        @Size(max = 128) String defaultBranch,
        @Size(max = 16) String status) {}
