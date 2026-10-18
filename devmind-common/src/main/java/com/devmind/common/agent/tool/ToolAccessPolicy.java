package com.devmind.common.agent.tool;

/**
 * 项目级数据权限 SPI（CAP-76 FR-21）。缺省缺席 = 仅 RBAC（当前现状）。
 *
 * <p>由 CAP-62 的 {@code ProjectAccessChecker} 适配实现；copilot 以
 * {@code ObjectProvider<ToolAccessPolicy>} 探测，不反向依赖 CAP-62。</p>
 */
public interface ToolAccessPolicy {

    /**
     * @param actor     平台账号
     * @param projectId 目标对象所属项目（工具必须先反查对象归属，不能用默认项目顶替）
     * @param risk      本次调用的实际风险（{@link ToolSpec#riskFor} 求值结果）
     * @param toolName  工具名（审计/日志）
     */
    boolean canAccess(String actor, String projectId, ToolRisk risk, String toolName);
}
