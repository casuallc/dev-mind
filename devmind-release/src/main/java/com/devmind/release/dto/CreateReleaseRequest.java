package com.devmind.release.dto;

/**
 * CAP-11 新建发版请求。
 * version 可选：不传且版本规则为可递增 semver（如 1.0.0）时自动 patch+1；
 * executor/agentNodeId 可选：缺省取项目发版配置（CAP-36：executor ∈ LOCAL|AGENT，AGENT 目标为 runner 节点）。
 */
public record CreateReleaseRequest(
        String projectId,
        String workItemId,
        Long buildId,
        String version,
        String executor,
        /** executor=AGENT 时的目标 runner 节点 id（可空 = 走节点路由链） */
        String agentNodeId,
        Boolean force) {
}
