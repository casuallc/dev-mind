package com.devmind.common.agent.tool;

/**
 * 工具内项目级权限判定助手（CAP-76 FR-21）：缺席策略 = 仅 RBAC。
 */
public final class ToolAccess {

    private ToolAccess() {
    }

    /** @return null = 允许；非 null = 应直接回给模型的拒绝结果 */
    public static ToolResult check(ToolAccessPolicy policy, String actor, String projectId,
                                   ToolRisk risk, String toolName) {
        if (policy == null || projectId == null || projectId.isBlank()) {
            return null;
        }
        if (policy.canAccess(actor, projectId, risk, toolName)) {
            return null;
        }
        return ToolResult.fail(ToolResult.PERMISSION_DENIED, "当前账号无权访问该项目");
    }
}
