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
 * @param costUsd        用量账本：累计成本（null = 历史会话或老 runner 无上报，下同）
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
        Instant finishedAt,
        Double costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Integer turnCount) {

    /**
     * 列表预览版：taskSpec/summary 全文是 @Lob 大字段，看板 3s 轮询全量回传会把带宽打满。
     * 列表 UI 只展示前 40 字，超出 max 截断补「…」；全文走 GET /sessions/{id}。
     */
    public SessionView withPreview(int max) {
        return new SessionView(id, projectId, workItemId, requirementId, previewOf(taskSpec, max),
                status, state, worktreePath, pid, model, previewOf(summary, max), agentNodeId,
                repoNames, createdBy, workspaceState, createdAt, updatedAt, finishedAt,
                costUsd, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, turnCount);
    }

    private static String previewOf(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
