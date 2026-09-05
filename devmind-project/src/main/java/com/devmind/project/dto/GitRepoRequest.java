package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * CAP-29 全局仓库登记请求。
 *
 * @param sourceType    LOCAL=登记服务器已存在路径；CLONE=服务端从 remoteUrl 克隆（默认 LOCAL）
 * @param localPath     LOCAL 必填；CLONE 忽略（路径由系统按 remoteUrl 推导）
 * @param integrationId CLONE 私有库的平台集成凭证（可空=匿名公开库）
 */
public record GitRepoRequest(
        @NotBlank @Size(max = 128) String name,
        @Pattern(regexp = "LOCAL|CLONE", message = "sourceType 仅支持 LOCAL/CLONE") String sourceType,
        @Size(max = 1024) String localPath,
        @Size(max = 512) String remoteUrl,
        Long integrationId,
        @Size(max = 128) String defaultBranch,
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status 仅支持 ACTIVE/DISABLED") String status) {
}
