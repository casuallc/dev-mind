package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 项目仓库（多库组合）创建/更新请求。role: CODE/DOCS/CONFIG，缺省 CODE；primary=true 时设为主库。
 * CAP-23：sourceType=LOCAL（默认）时 path 必填且必须是本地 git 仓库；
 * sourceType=CLONE 时 path 由服务端计算（忽略请求值），remoteUrl 必填，integrationId 可选（null=匿名克隆）。
 */
public record RepoRequest(
        @NotBlank String name,
        String path,
        String sourceType,
        String remoteUrl,
        Long integrationId,
        String defaultBranch,
        String role,
        Boolean primary,
        Integer sortOrder) {
}
