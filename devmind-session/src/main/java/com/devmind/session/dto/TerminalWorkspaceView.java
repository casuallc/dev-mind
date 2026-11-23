package com.devmind.session.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CAP-115 终端工作区视图。repos 为 repos_json 快照解析结果（字段对齐 SessionRepoEntity）；
 * lockOwner/lockAt 为当前占用锁（前端据此展示占用方与抢占提示）。
 */
public record TerminalWorkspaceView(
        String id,
        String projectId,
        String requirementId,
        String workItemId,
        String workspaceKey,
        String branch,
        String owner,
        String agentNodeId,
        String baseBranch,
        String state,
        String lockSessionId,
        String lockOwner,
        Instant lockAt,
        Instant lastActiveAt,
        String createdBy,
        Instant createdAt,
        Instant finalizedAt,
        List<Map<String, Object>> repos) {
}
