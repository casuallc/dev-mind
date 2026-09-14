package com.devmind.worklog.dto;

/**
 * CAP-41 FR-01：工作日志空间视图（WORKLOG 特殊项目 + 亲和节点状态）。
 *
 * @param exists      是否已初始化（false 时其余字段为 null，前端引导调 ensure）
 * @param projectId   WORKLOG 项目 id
 * @param projectName 项目名
 * @param path        逻辑占位路径（worklog://<owner>；真实目录在 runner 侧）
 * @param agentNodeId 亲和节点 id（创建时固化，不可改）
 * @param nodeOnline  亲和节点当前是否在线（null = 未知/无节点）
 */
public record WorkspaceView(boolean exists, String projectId, String projectName,
                            String path, String agentNodeId, Boolean nodeOnline) {

    public static WorkspaceView absent() {
        return new WorkspaceView(false, null, null, null, null, null);
    }
}
