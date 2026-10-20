package com.devmind.flow.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.flow.RequirementFlowService;
import com.devmind.project.DesignService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.SessionView;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-78 FR-01~03 流程域工具：开启 AI 规划 / 重新开发 / 工作单元起会话。
 *
 * <p>语义全部复用 {@link RequirementFlowService}（与网页按钮同源，CAP-78 决策 1），
 * 工具只做寻址解析（REQ/WI 编号 → 内部 id）与结果映射；终态需求、同需求进行中会话、
 * 无 WI、缺 spec 等校验由 Service 抛出 {@code DevMindException}，网关统一映射为
 * {@code CONFLICT}/{@code INVALID_ARGUMENTS}，工具不重复实现。</p>
 */
@Configuration
public class FlowTools {

    private static final String SINCE = "CAP-78 M1";

    private final RequirementFlowService flow;
    private final RequirementService requirements;
    private final WorkItemService workItems;
    private final DesignService designs;

    public FlowTools(RequirementFlowService flow, RequirementService requirements,
                     WorkItemService workItems, DesignService designs) {
        this.flow = flow;
        this.requirements = requirements;
        this.workItems = workItems;
        this.designs = designs;
    }

    @Bean
    public PlatformTool startRequirementPlanTool() {
        ToolSpec spec = new ToolSpec("start_requirement_plan", "开启 AI 规划",
                "对需求启动 AI 规划会话（等同网页「开启 AI 规划」按钮）：一个 [flow:plan] 会话一次产出"
                        + "分析/方案/工作单元清单（不改代码），完成后自动登记产出并自动启动开发会话。"
                        + "已有规划产出时再调用 = 重新规划（整体重跑，已有工作单元保留）。"
                        + "会创建 coding 会话产生模型费用；调用后生成确认单，用户确认后才真正启动。"
                        + "requirementId 支持 REQ-<seq>/内部 id/纯数字。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["requirementId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-flow", SINCE);
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String pid = projectId(args, ctx);
                String ref = ToolText.str(args, "requirementId");
                if (pid == null) {
                    return projectRequired();
                }
                if (ref == null) {
                    return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                            "请提供需求编号 requirementId（如 REQ-3）");
                }
                RequirementEntity req = requirements.resolveRef(pid, ref, null);
                SessionView view = flow.startPlan(pid, req.getId());
                String text = "已对 REQ-" + req.getSeq() + "（" + ToolText.oneLine(req.getTitle(), 60)
                        + "）启动 AI 规划会话 " + shortId(view.id()) + "（状态 " + view.status() + "）。"
                        + "规划完成后会自动登记分析/方案/工作单元并自动启动开发会话；"
                        + "期间可用 list_sessions 查看进度。";
                return ToolResult.ok(text, text, ToolResult.data(
                        "sessionId", view.id(), "status", view.status(),
                        "requirementId", req.getId(), "requirementSeq", req.getSeq()));
            }

            /** FR-01：预览必须区分首规划/重新规划；重新规划注明「整体重跑、已有 WI 保留」。 */
            @Override
            public String preview(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String pid = projectId(args, ctx);
                String ref = ToolText.str(args, "requirementId");
                if (pid == null || ref == null) {
                    return null; // 网关参数模板兜底
                }
                try {
                    RequirementEntity req = requirements.resolveRef(pid, ref, null);
                    boolean replan = !workItems.list(pid, req.getId()).isEmpty()
                            || !designs.list(pid, req.getId()).isEmpty();
                    StringBuilder sb = new StringBuilder();
                    sb.append(replan ? "**重新规划**" : "**开启 AI 规划**").append("\n");
                    sb.append("- 需求：REQ-").append(req.getSeq())
                            .append("（").append(ToolText.oneLine(req.getTitle(), 60)).append("）\n");
                    sb.append("- 动作：启动 [flow:plan] 规划会话，一次产出分析/方案/工作单元清单（不改代码）\n");
                    if (replan) {
                        sb.append("- ⚠️ 该需求已有规划产出：将**整体重跑**（产出落新版本），")
                                .append("已有工作单元**保留**不删除\n");
                    }
                    sb.append("- 规划完成后自动登记产出并自动启动开发会话（会产生模型费用）");
                    return sb.toString();
                } catch (RuntimeException e) {
                    // 预览阶段对象不存在不阻塞确认；execute 会报 NOT_FOUND
                    return null;
                }
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return projectId(args, ctx);
            }
        };
    }

    @Bean
    public PlatformTool startRequirementDevTool() {
        ToolSpec spec = new ToolSpec("start_requirement_dev", "重新开发（需求级开发会话）",
                "对已有工作单元清单的需求启动 [flow:dev] 需求级开发会话（等同网页「重新开发」）："
                        + "整份 TODO 工作单元清单交给一个会话一次做完，全部 TODO 工作单元置 IN_PROGRESS。"
                        + "需求还没有工作单元时会失败并提示先「开启 AI 规划」。"
                        + "调用后生成确认单，用户确认后才真正启动。requirementId 支持 REQ-<seq>/内部 id/纯数字。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["requirementId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-flow", SINCE);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = projectId(args, ctx);
            String ref = ToolText.str(args, "requirementId");
            if (pid == null) {
                return projectRequired();
            }
            if (ref == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供需求编号 requirementId（如 REQ-3）");
            }
            RequirementEntity req = requirements.resolveRef(pid, ref, null);
            SessionView view = flow.startDev(pid, req.getId());
            String text = "已对 REQ-" + req.getSeq() + "（" + ToolText.oneLine(req.getTitle(), 60)
                    + "）启动需求级开发会话 " + shortId(view.id()) + "（状态 " + view.status()
                    + "），整份工作单元清单将由该会话一次做完。";
            return ToolResult.ok(text, text, ToolResult.data(
                    "sessionId", view.id(), "status", view.status(),
                    "requirementId", req.getId(), "requirementSeq", req.getSeq()));
        }, args -> ToolText.str(args, "projectId"));
    }

    @Bean
    public PlatformTool startWorkItemSessionTool() {
        ToolSpec spec = new ToolSpec("start_work_item_session", "工作单元起会话",
                "对单个工作单元启动开发会话（人工逃生通道）：工作单元的 spec（执行说明）作为任务说明注入，"
                        + "TODO 状态推进为 IN_PROGRESS。缺 spec 的工作单元不能起会话（先在网页补充执行说明）。"
                        + "调用后生成确认单，用户确认后才真正启动。workItemId 支持 WI-<seq>/内部 id。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "workItemId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["workItemId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-flow", SINCE);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = projectId(args, ctx);
            String ref = ToolText.str(args, "workItemId");
            if (pid == null) {
                return projectRequired();
            }
            if (ref == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供工作单元编号 workItemId（如 WI-2）");
            }
            WorkItemEntity wi = workItems.resolveRef(pid, ref);
            SessionView view = flow.startWorkItemSession(pid, wi.getId());
            String text = "已为 WI-" + wi.getSeq() + "（" + ToolText.oneLine(wi.getTitle(), 60)
                    + "）启动开发会话 " + shortId(view.id()) + "（状态 " + view.status()
                    + "），任务说明取自该工作单元的 spec。";
            return ToolResult.ok(text, text, ToolResult.data(
                    "sessionId", view.id(), "status", view.status(),
                    "workItemId", wi.getId(), "workItemSeq", wi.getSeq()));
        }, args -> ToolText.str(args, "projectId"));
    }

    // ---------------- 小助手 ----------------

    private static String projectId(Map<String, Object> args, ToolContext ctx) {
        String fromArgs = ToolText.str(args, "projectId");
        return fromArgs != null ? fromArgs
                : (ctx.projectId() == null || ctx.projectId().isBlank() ? null : ctx.projectId());
    }

    private static ToolResult projectRequired() {
        return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
    }

    private static String shortId(String id) {
        return id == null ? "-" : (id.length() <= 8 ? id : id.substring(0, 8));
    }
}
