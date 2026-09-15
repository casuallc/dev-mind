package com.devmind.session.dto;

import com.devmind.common.agent.runtime.SessionState;

import java.time.Instant;
import java.util.List;

/**
 * 会话视图（看板/详情）。status 为实时状态（内存运行时优先）。
 *
 * @param repoNames      CAP-31 关联仓库名（session_repos 快照；单库 = 1 个元素）
 * @param createdBy      创建者用户名（CAP-42 收口鉴权：本人或 admin）
 * @param workspaceState CAP-42 固定工作区收口状态（OPEN/FINALIZED；null = 旧会话或非 repo 会话）
 */
public record SessionView(
        String id,
        String projectId,
        String workItemId,
        String requirementId,
        String taskSpec,
        String status,
        SessionState state,
        String worktreePath,
        Long pid,
        String model,
        String summary,
        String agentNodeId,
        List<String> repoNames,
        String createdBy,
        String workspaceState,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {
}
