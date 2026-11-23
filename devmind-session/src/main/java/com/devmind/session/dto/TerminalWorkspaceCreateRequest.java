package com.devmind.session.dto;

/**
 * CAP-115 新建终端工作区请求。projectId 可空（挂需求/工作单元时反推，与 CreateSessionRequest 同约定）。
 */
public record TerminalWorkspaceCreateRequest(
        String projectId,
        String requirementId,
        String workItemId,
        String baseBranch,
        String agentNodeId) {
}
