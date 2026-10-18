package com.devmind.integration.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.integration.dto.JiraSyncConfigView;
import com.devmind.integration.dto.JiraSyncRunView;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.dto.JiraWorklogResultView;
import com.devmind.integration.service.JiraIssueActionService;
import com.devmind.integration.service.JiraSyncService;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M6 Jira 工具：工作流转换查询/执行、登记远端工时、同步配置查询与手动同步。
 *
 * <p>需求寻址统一走 {@link RequirementService#resolveRef}：内部 id、展示编号 {@code REQ-<seq>}、
 * 纯数字 seq 都接受；项目上下文取参数 projectId，缺省用会话绑定项目。</p>
 *
 * <p>所有写工具都要求需求必须是 JIRA 来源（JiraIssueActionService 内部校验）；写操作经确认单执行，
 * 远端工时会落到当前用户绑定的个人 Jira 账号（无绑定回落集成账号）。</p>
 */
@Configuration
public class JiraTools {

    private static final String M = "CAP-76 M6";

    private final JiraIssueActionService actions;
    private final JiraSyncService syncs;
    private final RequirementService requirements;

    public JiraTools(JiraIssueActionService actions, JiraSyncService syncs,
                     RequirementService requirements) {
        this.actions = actions;
        this.syncs = syncs;
        this.requirements = requirements;
    }

    @Bean
    public PlatformTool listJiraTransitionsTool() {
        ToolSpec spec = new ToolSpec("list_jira_transitions", "查询 Jira 可用转换",
                "列出需求关联 Jira issue 当前可用的工作流转换（id/名称/目标状态）。"
                        + "requirementId 可用展示编号（如 REQ-12）或 seq；用于回答「这张单能流转到哪些状态」。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-integration", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            RequirementEntity req = requirement(args, ctx);
            List<JiraTransitionView> list = actions.listTransitions(req.getProjectId(), req.getId());
            if (list.isEmpty()) {
                return ToolResult.ok("该 issue 当前没有可用的工作流转换。");
            }
            StringBuilder sb = new StringBuilder("需求 " + req.getSeq() + " 关联 issue 可用转换 "
                    + list.size() + " 个：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JiraTransitionView t : list) {
                sb.append("\n- [").append(t.id()).append("] ").append(t.name());
                if (t.toStatus() != null && !t.toStatus().isBlank()) {
                    sb.append(" → ").append(t.toStatus());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", t.id());
                row.put("name", t.name());
                if (t.toStatus() != null) {
                    row.put("toStatus", t.toStatus());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("requirementId", req.getId(), "transitions", rows));
        }, this::requirementProject);
    }

    @Bean
    public PlatformTool transitJiraIssueTool() {
        ToolSpec spec = new ToolSpec("transit_jira_issue", "执行 Jira 状态转换",
                "对需求关联的 Jira issue 执行一次工作流转换（transitionId 取 list_jira_transitions 返回的 id）。"
                        + "只回写远端与托管字段，不改本地需求 status。调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1},
                            "transitionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["transitionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-integration", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String transitionId = ToolText.str(args, "transitionId");
            if (transitionId == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供 transitionId");
            }
            RequirementEntity req = requirement(args, ctx);
            JiraTransitionResultView result =
                    actions.transit(req.getProjectId(), req.getId(), transitionId);
            JiraTransitionView t = result.transition();
            String text = "已对需求 " + req.getSeq() + " 关联 issue 执行转换「" + t.name() + "」"
                    + (result.remoteStatus() == null ? "" : "，当前远端状态：" + result.remoteStatus());
            return ToolResult.ok(text, text, ToolResult.data(
                    "requirementId", req.getId(), "transitionId", t.id(),
                    "transitionName", t.name(),
                    "remoteStatus", result.remoteStatus() == null ? "" : result.remoteStatus()));
        }, this::requirementProject);
    }

    @Bean
    public PlatformTool logJiraWorklogTool() {
        ToolSpec spec = new ToolSpec("log_jira_worklog", "登记 Jira 工时",
                "在需求关联的 Jira issue 上登记工时（hours 以小时计，支持小数；comment 可空）。"
                        + "按当前用户的个人 Jira 账号写入（未绑定用集成账号）。调用后生成确认单，用户确认后才写远端。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1},
                            "hours": {"type": "number", "minimum": 0.01, "maximum": 100},
                            "comment": {"type": "string", "maxLength": 2000}
                          },
                          "required": ["hours"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-integration", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            if (!(args.get("hours") instanceof Number hoursArg)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供工时 hours（小时）");
            }
            long seconds = Math.round(hoursArg.doubleValue() * 3600);
            if (seconds <= 0 || seconds > 360_000) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "工时需在 0.01~100 小时之间");
            }
            RequirementEntity req = requirement(args, ctx);
            JiraWorklogResultView result = actions.logWork(
                    req.getProjectId(), req.getId(), seconds, ToolText.str(args, "comment"));
            String text = "已在需求 " + req.getSeq() + " 关联 issue 登记工时 "
                    + formatSeconds(seconds)
                    + (result.remoteStatus() == null ? "" : "，当前远端状态：" + result.remoteStatus());
            return ToolResult.ok(text, text, ToolResult.data(
                    "requirementId", req.getId(), "seconds", seconds,
                    "remoteStatus", result.remoteStatus() == null ? "" : result.remoteStatus()));
        }, this::requirementProject);
    }

    @Bean
    public PlatformTool listJiraSyncConfigsTool() {
        ToolSpec spec = new ToolSpec("list_jira_sync_configs", "查询 Jira 同步配置",
                "列出项目下 Jira 同步配置（配置 id/集成/Jira 项目 key/JQL/是否启用/上次同步时间与计数/最近错误）。"
                        + "用于回答「Jira 同步配了哪些、跑得怎么样」。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-integration", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            String pid = projectId(call.args(), ctx);
            if (pid == null) {
                return projectRequired();
            }
            List<JiraSyncConfigView> list = syncs.list(pid);
            if (list.isEmpty()) {
                return ToolResult.ok("该项目还没有配置 Jira 同步。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 条 Jira 同步配置：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JiraSyncConfigView c : list) {
                sb.append("\n- [").append(c.id()).append("] ").append(c.jiraProjectKey())
                        .append(" · ").append(c.enabled() ? "启用" : "停用")
                        .append(" · 上次同步 ").append(ToolText.time(c.lastSyncAt()));
                if (c.lastError() != null && !c.lastError().isBlank()) {
                    sb.append(" · 错误: ").append(ToolText.oneLine(c.lastError(), 120));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", c.id());
                row.put("integrationId", c.integrationId());
                row.put("jiraProjectKey", c.jiraProjectKey());
                row.put("jql", c.jql());
                row.put("enabled", c.enabled());
                row.put("pollIntervalSec", c.pollIntervalSec());
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("projectId", pid, "configs", rows));
        });
    }

    @Bean
    public PlatformTool runJiraSyncTool() {
        ToolSpec spec = new ToolSpec("run_jira_sync", "手动触发 Jira 同步",
                "立即执行一次指定配置的 Jira 同步（拉取 JQL 命中的 issue，新建/刷新需求）。"
                        + "configId 取 list_jira_sync_configs 返回的 id；同步期间同一时间只允许一个任务。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "configId": {"type": "integer", "minimum": 1}
                          },
                          "required": ["configId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-integration", M, 180);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = projectId(args, ctx);
            if (pid == null) {
                return projectRequired();
            }
            if (!(args.get("configId") instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供同步配置 id configId");
            }
            long configId = n.longValue();
            JiraSyncRunView run = syncs.syncNow(pid, configId);
            if (run.error() != null && !run.error().isBlank()) {
                return ToolResult.fail(ToolResult.UPSTREAM_ERROR,
                        "Jira 同步失败: " + ToolText.oneLine(run.error(), 300));
            }
            String text = "Jira 同步完成：新建 " + run.imported() + " 条，刷新 " + run.updated()
                    + " 条，跳过 " + run.skipped() + " 条（拉取 " + run.pages() + " 页）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "configId", configId, "imported", run.imported(),
                    "updated", run.updated(), "skipped", run.skipped(), "pages", run.pages()));
        });
    }

    // ---------------- 小助手 ----------------

    private RequirementEntity requirement(Map<String, Object> args, ToolContext ctx) {
        String ref = ToolText.str(args, "requirementId");
        Long seq = args.get("seq") instanceof Number n ? n.longValue() : null;
        if (ref == null && seq == null) {
            throw new IllegalArgumentException("请提供需求 id 或编号 REQ-<seq>");
        }
        return requirements.resolveRef(projectId(args, ctx), ref, seq);
    }

    /** 网关归属反查：需求解析成功用其所属项目；解析失败回落参数里的项目，让 execute 报准确错误。 */
    private String requirementProject(Map<String, Object> args) {
        String pid = ToolText.str(args, "projectId");
        try {
            Long seq = args.get("seq") instanceof Number n ? n.longValue() : null;
            return requirements.resolveRef(pid, ToolText.str(args, "requirementId"), seq).getProjectId();
        } catch (RuntimeException e) {
            return pid;
        }
    }

    private static String projectId(Map<String, Object> args, ToolContext ctx) {
        String fromArgs = ToolText.str(args, "projectId");
        if (fromArgs != null) {
            return fromArgs;
        }
        return ctx.projectId() == null || ctx.projectId().isBlank() ? null : ctx.projectId();
    }

    private static ToolResult projectRequired() {
        return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
    }

    private static String formatSeconds(long seconds) {
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        return String.format(Locale.ROOT, "%.1fh", seconds / 3600.0);
    }
}
