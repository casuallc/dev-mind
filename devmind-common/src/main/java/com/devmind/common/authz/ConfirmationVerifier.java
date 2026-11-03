package com.devmind.common.authz;

/**
 * CAP-108 FR-06/§7.1 确认凭据校验 SPI（copilot 实现）：
 * 应用边界对 AI 来源的写命令校验绑定的服务端确认凭据——验证签发方、属主、过期、
 * 参数/目标绑定与单次消费，不能只信任传来的 actionId。
 * 实现缺失时 AI 写命令一律拒绝（不放行为仅 RBAC）。
 */
public interface ConfirmationVerifier {

    /**
     * @return null = 确认凭据有效；非 null = 拒绝原因（引擎归一为 CONFIRMATION_REQUIRED 拒绝）
     */
    String verify(ExecutionContext ctx, OperationSpec operation, ResourceTarget target);
}
