package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 项目仓库（多库组合）创建/更新请求。role: CODE/DOCS/CONFIG，缺省 CODE；primary=true 时设为主库。
 */
public record RepoRequest(
        @NotBlank String name,
        @NotBlank String path,
        String remoteUrl,
        String defaultBranch,
        String role,
        Boolean primary,
        Integer sortOrder) {
}
