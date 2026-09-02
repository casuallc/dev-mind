package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 项目创建/更新请求。
 * CAP-23：sourceType=LOCAL（默认）时 path 必填且必须是本地 git 仓库；
 * sourceType=CLONE 时 path 由服务端计算（忽略请求值），remoteUrl 必填，integrationId 可选（null=匿名克隆公开仓库）。
 */
public record ProjectRequest(
        @NotBlank String name,
        String path,
        String sourceType,
        String remoteUrl,
        Long integrationId,
        String defaultBranch,
        List<String> tags,
        String description,
        String status,
        String apiDocSource,
        Boolean autoRegressionOnDeploy) {
}
