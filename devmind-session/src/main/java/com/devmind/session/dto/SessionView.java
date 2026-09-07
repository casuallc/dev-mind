package com.devmind.session.dto;

import com.devmind.common.agent.runtime.SessionState;

import java.time.Instant;
import java.util.List;

/**
 * 会话视图（看板/详情）。status 为实时状态（内存运行时优先）。
 *
 * @param repoNames CAP-31 关联仓库名（session_repos 快照；单库 = 1 个元素）
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
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {
}
