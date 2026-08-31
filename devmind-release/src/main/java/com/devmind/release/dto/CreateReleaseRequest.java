package com.devmind.release.dto;

/**
 * CAP-11 新建发版请求。
 * version 可选：不传且版本规则为可递增 semver（如 1.0.0）时自动 patch+1；
 * executor/serverId 可选：缺省取项目发版配置。
 */
public record CreateReleaseRequest(
        String projectId,
        String workItemId,
        Long buildId,
        String version,
        String executor,
        Long serverId,
        Boolean force) {
}
