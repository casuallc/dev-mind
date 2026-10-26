package com.devmind.worklog.dto;

/**
 * 工作日志空间视图（CAP-89：仓口径字段随 git 链路退役整体移除——空间只剩 WORKLOG 项目
 * 与亲和节点，服务于「AI 生成/对话」链路；读/编辑直接落库，不再依赖节点）。
 *
 * @param exists      是否已初始化（false 时其余字段为 null，前端引导调 ensure）
 * @param projectId   WORKLOG 项目 id（null = 未建——无默认节点时降级缺位，launch 时 409）
 * @param agentNodeId 亲和节点 id（创建时固化，不可改）
 * @param nodeOnline  亲和节点当前是否在线（null = 未知/无节点；离线仅影响 AI 生成与对话）
 */
public record WorkspaceView(boolean exists, String projectId, String agentNodeId, Boolean nodeOnline) {

    public static WorkspaceView absent() {
        return new WorkspaceView(false, null, null, null);
    }
}
