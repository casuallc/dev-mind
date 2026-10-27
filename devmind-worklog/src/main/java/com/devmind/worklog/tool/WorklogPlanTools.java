package com.devmind.worklog.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.service.WorklogPlanService;
import com.devmind.worklog.service.WorklogWeeks;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M9 周计划工具：查某周计划（含上周未完成提示）、新增/修改计划项。
 * 日计划已并入条目（CAP-90）：周计划是唯一计划粒度；数据归属当前登录账号（Service 内部
 * 走 identity.currentActor()），无需项目上下文。更新为读-合并-写——未传字段保持不变，
 * 因此不能通过更新清空某字段（要清空请删除重建，删除工具刻意不提供）。
 */
@Configuration
public class WorklogPlanTools {

    private static final String M = "CAP-76 M9";
    private static final List<String> PLAN_TYPES =
            List.of("DEV", "SUPPORT", "MEETING", "RESEARCH", "OTHER");
    private static final List<String> STATUSES = List.of("TODO", "DOING", "DONE", "DROPPED");

    private final WorklogPlanService plans;

    public WorklogPlanTools(WorklogPlanService plans) {
        this.plans = plans;
    }

    @Bean
    public PlatformTool listWorklogPlansTool() {
        ToolSpec spec = new ToolSpec("list_worklog_plans", "查询周计划",
                "查询本人某周的周计划项（id/标题/状态/类型/预估工时/关联 KR/需求/Jira），"
                        + "并附上一周未完成（TODO/DOING）项提示。weekStart 为该周任意一天（yyyy-MM-dd），"
                        + "缺省本周。日计划已并入工作日志条目，查日执行用 list_worklog_entries。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "weekStart": {"type": "string", "maxLength": 10}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            LocalDate date = WorklogToolsHelper.dateOr(ToolText.str(call.args(), "weekStart"), LocalDate.now());
            PlanListView view = plans.list("WEEK", date);
            LocalDate monday = WorklogWeeks.mondayOf(date);
            StringBuilder sb = new StringBuilder(monday + " 周计划共 " + view.items().size() + " 项：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PlanItemView p : view.items()) {
                sb.append("\n- #").append(p.id()).append(" [").append(p.status()).append("] ")
                        .append(p.title());
                if (p.planType() != null) {
                    sb.append(" · ").append(p.planType());
                }
                if (p.estimateHours() != null) {
                    sb.append(" · 预估 ").append(WorklogToolsHelper.hours(p.estimateHours())).append("h");
                }
                String krTitle = p.krId() == null ? null : view.krTitles().get(p.krId());
                if (krTitle != null) {
                    sb.append(" · KR=").append(krTitle);
                }
                if (p.requirementId() != null && !p.requirementId().isBlank()) {
                    sb.append(" · 需求=").append(p.requirementId());
                }
                if (p.jiraIssueKey() != null && !p.jiraIssueKey().isBlank()) {
                    sb.append(" · Jira=").append(p.jiraIssueKey());
                }
                rows.add(row(p));
            }
            List<Map<String, Object>> carryRows = new ArrayList<>();
            if (!view.carryoverItems().isEmpty()) {
                sb.append("\n上周（").append(view.carryoverFrom()).append("）未完成 ")
                        .append(view.carryoverItems().size()).append(" 项：");
                for (PlanItemView p : view.carryoverItems()) {
                    sb.append("\n- #").append(p.id()).append(" [").append(p.status()).append("] ")
                            .append(p.title());
                    carryRows.add(row(p));
                }
            }
            if (view.items().isEmpty() && view.carryoverItems().isEmpty()) {
                sb.setLength(0);
                sb.append(monday).append(" 这一周还没有计划项，上周也没有未完成项。");
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data(
                    "weekStart", monday.toString(), "items", rows, "carryoverFrom",
                    String.valueOf(view.carryoverFrom()), "carryoverItems", carryRows));
        });
    }

    @Bean
    public PlatformTool createWorklogPlanItemTool() {
        ToolSpec spec = new ToolSpec("create_worklog_plan_item", "新增周计划项",
                "在本人某周的周计划中新增一项（初始状态 TODO）。weekStart 为该周任意一天，缺省本周；"
                        + "planType 可选 DEV/SUPPORT/MEETING/RESEARCH/OTHER（缺省 DEV）；hours 为预估工时"
                        + "（0~24，可空）；krId 关联本人当季 KEY_RESULT 目标（用 list_worklog_goals 查 id）；"
                        + "可关联需求或 Jira issue。调用后生成确认单，用户确认后才落库。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "title": {"type": "string", "maxLength": 256},
                            "weekStart": {"type": "string", "maxLength": 10},
                            "content": {"type": "string", "maxLength": 20000},
                            "planType": {"type": "string",
                                         "enum": ["DEV", "SUPPORT", "MEETING", "RESEARCH", "OTHER"]},
                            "hours": {"type": "number", "minimum": 0, "maximum": 24},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "jiraIssueKey": {"type": "string", "maxLength": 64},
                            "krId": {"type": "integer", "minimum": 1}
                          },
                          "required": ["title"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String title = ToolText.str(args, "title");
            if (title == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供计划项标题");
            }
            LocalDate date = WorklogToolsHelper.dateOr(ToolText.str(args, "weekStart"), LocalDate.now());
            PlanItemView view = plans.create(new PlanItemRequest("WEEK", date, title,
                    ToolText.str(args, "content"), ToolText.str(args, "planType"),
                    WorklogToolsHelper.hoursArg(args), ToolText.str(args, "requirementId"),
                    ToolText.str(args, "jiraIssueKey"), WorklogToolsHelper.longArg(args, "krId"), null));
            String text = "已在 " + view.planDate() + " 周计划新增：" + view.title()
                    + (view.estimateHours() == null ? ""
                       : "（预估 " + WorklogToolsHelper.hours(view.estimateHours()) + "h）");
            return ToolResult.ok(text, text, ToolResult.data(
                    "id", view.id(), "planDate", String.valueOf(view.planDate()), "status", view.status()));
        });
    }

    @Bean
    public PlatformTool updateWorklogPlanItemTool() {
        ToolSpec spec = new ToolSpec("update_worklog_plan_item", "修改周计划项",
                "修改本人周计划项的标题/内容/类型/预估工时/关联（KR/需求/Jira）或状态"
                        + "（TODO/DOING/DONE/DROPPED）。只改传入字段，未传字段保持不变（读-合并-写，"
                        + "不支持把字段清空）；已滚转（CARRIED）项只读。调用后生成确认单。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "id": {"type": "integer", "minimum": 1},
                            "title": {"type": "string", "maxLength": 256},
                            "content": {"type": "string", "maxLength": 20000},
                            "planType": {"type": "string",
                                         "enum": ["DEV", "SUPPORT", "MEETING", "RESEARCH", "OTHER"]},
                            "hours": {"type": "number", "minimum": 0, "maximum": 24},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "jiraIssueKey": {"type": "string", "maxLength": 64},
                            "krId": {"type": "integer", "minimum": 1},
                            "status": {"type": "string", "enum": ["TODO", "DOING", "DONE", "DROPPED"]}
                          },
                          "required": ["id"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            Long id = WorklogToolsHelper.longArg(args, "id");
            if (id == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供计划项 id（用 list_worklog_plans 查）");
            }
            if (ToolText.str(args, "title") == null && ToolText.str(args, "content") == null
                    && ToolText.str(args, "planType") == null && args.get("hours") == null
                    && ToolText.str(args, "requirementId") == null && ToolText.str(args, "jiraIssueKey") == null
                    && args.get("krId") == null && ToolText.str(args, "status") == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "至少提供一个要修改的字段");
            }
            PlanItemView cur = plans.get(id);
            String title = ToolText.str(args, "title") != null ? ToolText.str(args, "title") : cur.title();
            PlanItemView view = plans.update(id, new PlanItemRequest(cur.scope(), cur.planDate(), title,
                    ToolText.str(args, "content") != null ? ToolText.str(args, "content") : cur.content(),
                    ToolText.str(args, "planType") != null ? ToolText.str(args, "planType") : cur.planType(),
                    args.get("hours") != null ? WorklogToolsHelper.hoursArg(args) : cur.estimateHours(),
                    ToolText.str(args, "requirementId") != null
                            ? ToolText.str(args, "requirementId") : cur.requirementId(),
                    ToolText.str(args, "jiraIssueKey") != null
                            ? ToolText.str(args, "jiraIssueKey") : cur.jiraIssueKey(),
                    args.get("krId") != null ? WorklogToolsHelper.longArg(args, "krId") : cur.krId(),
                    ToolText.str(args, "status")));
            String text = "已更新计划项 #" + view.id() + "：" + view.title() + "（" + view.status() + "）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "id", view.id(), "planDate", String.valueOf(view.planDate()), "status", view.status()));
        });
    }

    private static Map<String, Object> row(PlanItemView p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.id());
        row.put("planDate", String.valueOf(p.planDate()));
        row.put("title", p.title());
        row.put("status", p.status());
        if (p.planType() != null) {
            row.put("planType", p.planType());
        }
        if (p.estimateHours() != null) {
            row.put("estimateHours", p.estimateHours());
        }
        if (p.krId() != null) {
            row.put("krId", p.krId());
        }
        if (p.requirementId() != null && !p.requirementId().isBlank()) {
            row.put("requirementId", p.requirementId());
        }
        if (p.jiraIssueKey() != null && !p.jiraIssueKey().isBlank()) {
            row.put("jiraIssueKey", p.jiraIssueKey());
        }
        return row;
    }
}
