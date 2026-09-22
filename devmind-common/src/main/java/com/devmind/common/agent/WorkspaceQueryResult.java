package com.devmind.common.agent;

import java.util.Map;

/**
 * CAP-54 workspace_query 应答：runner 对 tree/file/diff/status 查询的回执。
 *
 * @param ok      查询是否成功（路径越界/会话不在本节点/非 git 目录求 diff 等 → false + error）
 * @param payload 结果负载（action 各异：tree=entries[]，file=content，diff=diff 文本，
 *                status=完整快照；成功时非空）
 * @param error   失败原因（用户可读）
 */
public record WorkspaceQueryResult(boolean ok, Map<String, Object> payload, String error) {

    public static WorkspaceQueryResult ok(Map<String, Object> payload) {
        return new WorkspaceQueryResult(true, payload, null);
    }

    public static WorkspaceQueryResult failed(String error) {
        return new WorkspaceQueryResult(false, null, error);
    }
}
