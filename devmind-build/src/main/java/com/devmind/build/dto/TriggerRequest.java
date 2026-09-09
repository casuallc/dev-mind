package com.devmind.build.dto;

/**
 * 触发构建（FR-03/07）：commit/branch 不传则本地执行时取当前 HEAD；executor/agentNodeId 可临时覆盖配置。
 *
 * @param executor       LOCAL | AGENT（CAP-36：REMOTE/SSH 已下线）
 * @param agentNodeId    AGENT 执行的目标节点 id（可空 = 路由链：项目默认 > 平台默认 > 标签）
 * @param requiredLabels AGENT 调度的标签要求（CSV，可空）
 */
public record TriggerRequest(String commit, String branch, String executor, String agentNodeId,
                             String requiredLabels, String workItemId) {
}
