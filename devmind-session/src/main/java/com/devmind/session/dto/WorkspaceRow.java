package com.devmind.session.dto;

import java.time.Instant;
import java.util.List;

/**
 * 工作区管理 tab 行：按工作区桶（keyed = {@code <workspaceKey>@<owner>}；旧布局 = {@code work@<owner>}）
 * 聚合 sessions 表里有固定工作区的会话，一行 = 节点上一块工作区。
 * 收口明细不随行返回（走 /api/sessions/{detailSessionId}/finalize-detail 按需取）。
 *
 * @param actionSessionId 动作目标会话（收口/清理用；优先取 OPEN 成员，否则最新成员）
 * @param detailSessionId 收口明细所在会话（无 = 该工作区从未收口成功过）
 */
public record WorkspaceRow(String workspaceKey, String owner, String requirementId,
                           String requirementTitle, String branch, List<String> repoNames,
                           String agentNodeId, String state,
                           String actionSessionId, String detailSessionId,
                           int sessionCount, Instant finalizedAt, Instant updatedAt) {
}
