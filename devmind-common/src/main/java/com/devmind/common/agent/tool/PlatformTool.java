package com.devmind.common.agent.tool;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 平台工具 SPI（CAP-76 FR-02）。由各能力模块实现并 {@code @Component} 暴露，copilot 只编排。
 *
 * <p>实现必须线程安全；同一确认单至多执行一次由网关原子抢占保证，业务幂等不强制。</p>
 */
public interface PlatformTool {

    ToolSpec spec();

    ToolResult execute(ToolCall call, ToolContext ctx);

    /** 便捷工厂：无状态工具直接用 spec + 执行函数声明（需要 preview 的工具仍走实现类）。 */
    static PlatformTool of(ToolSpec spec, BiFunction<ToolCall, ToolContext, ToolResult> body) {
        return of(spec, body, null);
    }

    /**
     * 便捷工厂（带对象归属反查）：按对象 id 操作的工具用它把「对象所属项目」交给网关做权限校验，
     * 不能用对话默认项目顶替（FR-21）。
     */
    static PlatformTool of(ToolSpec spec, BiFunction<ToolCall, ToolContext, ToolResult> body,
                           Function<Map<String, Object>, String> projectResolver) {
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                return body.apply(call, ctx);
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return projectResolver == null
                        ? PlatformTool.super.resolveProjectId(args, ctx)
                        : projectResolver.apply(args);
            }
        };
    }

    /** 可选：生成写操作的中文预览；null = 网关用参数模板生成（FR-10）。 */
    default String preview(ToolCall call, ToolContext ctx) {
        return null;
    }

    /**
     * 写操作归属项目反查（CAP-76 FR-10/FR-15/FR-21）：用于同批写项目一致性校验、确认单落
     * {@code project_id}，以及确认执行时的项目级权限重校验。参数已通过 schema 校验。
     * 只读工具与参数自带 projectId 的工具可返回该值或 null。
     */
    default String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
        Object projectId = args == null ? null : args.get("projectId");
        return projectId == null ? null : String.valueOf(projectId);
    }
}
