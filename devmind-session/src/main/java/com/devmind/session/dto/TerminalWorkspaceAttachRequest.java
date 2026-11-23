package com.devmind.session.dto;

/**
 * CAP-115 挂接既有工作区键为终端工作区。workspaceKey 形如 req-<需求id>/sid-<会话id>/term-<短id>；
 * req- 键的需求 id 从键推导（projectId 可省），其它键需显式 projectId。
 */
public record TerminalWorkspaceAttachRequest(
        String workspaceKey,
        String projectId,
        String baseBranch,
        String agentNodeId) {
}
