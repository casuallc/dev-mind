package com.devmind.common.agent.tool;

import java.util.Map;

/**
 * 工具契约（CAP-76 FR-02/03）：模型、工具目录、OpenAPI 导出共用这一份。
 *
 * @param name             snake_case 稳定名（{@code [a-z][a-z0-9_]*}）
 * @param title            中文展示名（确认卡/审计）
 * @param description      给模型的能力说明（何时用/返回什么）
 * @param parametersSchema JSON Schema 子集（object/properties/required/enum/type/…）
 * @param risk             静态风险级别；动态风险见 {@link #riskFor(Map)}
 * @param minRole          VIEWER / DEVELOPER / ADMIN
 * @param requiresProject  无项目上下文时是否必须先问用户
 * @param ownerModule      归属模块（如 devmind-project）
 * @param since            首次交付版本（如 CAP-76 M1）
 * @param timeoutSeconds   单次执行超时；0 = 用全局 tool-timeout-seconds
 * @param riskEvaluator    动态风险求值器（可空）；为空时 {@link #riskFor(Map)} 返回静态 {@link #risk}
 */
public record ToolSpec(
        String name,
        String title,
        String description,
        String parametersSchema,
        ToolRisk risk,
        String minRole,
        boolean requiresProject,
        String ownerModule,
        String since,
        int timeoutSeconds,
        RiskEvaluator riskEvaluator) {

    /** 动态风险求值器：只允许基于 schema 校验后的参数判断（如目标状态 DONE/CANCELLED）。 */
    @FunctionalInterface
    public interface RiskEvaluator {
        ToolRisk evaluate(Map<String, Object> args);
    }

    /** 兼容构造器：不单独设置超时的工具。 */
    public ToolSpec(String name, String title, String description, String parametersSchema,
                    ToolRisk risk, String minRole, boolean requiresProject,
                    String ownerModule, String since) {
        this(name, title, description, parametersSchema, risk, minRole, requiresProject,
                ownerModule, since, 0, null);
    }

    /** 兼容构造器：固定超时、无动态风险的工具。 */
    public ToolSpec(String name, String title, String description, String parametersSchema,
                    ToolRisk risk, String minRole, boolean requiresProject,
                    String ownerModule, String since, int timeoutSeconds) {
        this(name, title, description, parametersSchema, risk, minRole, requiresProject,
                ownerModule, since, timeoutSeconds, null);
    }

    /**
     * 动态风险：默认等于 {@link #risk}。只允许基于 args 判断（如目标状态 DONE/CANCELLED）；
     * 需要查库才能判断的风险（如环境是否 PROD）不要放这里——直接把工具静态定为 DANGEROUS。
     *
     * <p>求值时机：参数 schema 校验通过之后，{@code args} 是校验后的值。参与判断的枚举/字符串
     * 必须按目标 Service 的归一化规则处理（例如状态 {@code trim().toUpperCase(Locale.ROOT)}），
     * 否则 {@code "done"} / {@code " DONE "} 会把 DANGEROUS 降成 WRITE，被裸「确认」绕过。</p>
     */
    public ToolRisk riskFor(Map<String, Object> args) {
        return riskEvaluator == null ? risk : riskEvaluator.evaluate(args);
    }
}
