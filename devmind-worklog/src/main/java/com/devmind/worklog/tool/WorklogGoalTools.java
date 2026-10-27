package com.devmind.worklog.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.service.WorklogGoalService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M9 目标（OKR）工具：查整年目标树（年度 O + Q1~Q4 季区，KR 附进度与派生统计）、
 * 新增/修改目标。数据归属当前登录账号（Service 内部走 identity.currentActor()），无需项目上下文。
 * 更新为读-合并-写——未传字段保持不变；scope/period/kind/framework 是身份属性不可改（要变请新建）。
 */
@Configuration
public class WorklogGoalTools {

    private static final String M = "CAP-76 M9";

    private final WorklogGoalService goals;

    public WorklogGoalTools(WorklogGoalService goals) {
        this.goals = goals;
    }

    @Bean
    public PlatformTool listWorklogGoalsTool() {
        ToolSpec spec = new ToolSpec("list_worklog_goals", "查询目标树",
                "查询本人某年的 OKR 目标树：年度 O 及其 KR、Q1~Q4 四个季度区（O 嵌套 KR）。"
                        + "每条带 id（创建/修改目标时引用）、状态、进度；KR 另附关联周计划的完成度与已登记工时。"
                        + "year 缺省今年。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "year": {"type": "integer", "minimum": 2000, "maximum": 2100}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            int year = ToolText.intArg(call.args(), "year", LocalDate.now().getYear(), 2000, 2100);
            GoalTreeView tree = goals.tree(year);
            StringBuilder sb = new StringBuilder(year + " 年目标树：");
            List<Map<String, Object>> rows = new ArrayList<>();
            sb.append("\n【年度】");
            appendNodes(sb, rows, tree.yearObjectives());
            for (GoalTreeView.QuarterSection q : tree.quarters()) {
                sb.append("\n【").append(q.period()).append("】");
                appendNodes(sb, rows, q.objectives());
            }
            if (rows.isEmpty()) {
                sb.setLength(0);
                sb.append(year).append(" 年还没有任何目标。");
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("year", year, "goals", rows));
        });
    }

    @Bean
    public PlatformTool createWorklogGoalTool() {
        ToolSpec spec = new ToolSpec("create_worklog_goal", "创建目标",
                "创建本人 OKR 目标（初始状态 ACTIVE、进度 0）。scope=YEAR 时 period 形如 2026，"
                        + "scope=QUARTER 时形如 2026-Q1；kind=OBJECTIVE（O）或 KEY_RESULT（KR）。"
                        + "层级规则：KR 必须挂同周期的 O（parentId 必填）；季度 O 可选挂同年年度 O；年度 O 不能有父。"
                        + "weight 为 0~100 权重（可空）。调用后生成确认单，用户确认后才落库。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "scope": {"type": "string", "enum": ["YEAR", "QUARTER"]},
                            "period": {"type": "string", "maxLength": 8},
                            "kind": {"type": "string", "enum": ["OBJECTIVE", "KEY_RESULT"]},
                            "parentId": {"type": "integer", "minimum": 1},
                            "title": {"type": "string", "maxLength": 256},
                            "content": {"type": "string", "maxLength": 20000},
                            "weight": {"type": "integer", "minimum": 0, "maximum": 100}
                          },
                          "required": ["scope", "period", "kind", "title"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String scope = ToolText.str(args, "scope");
            String period = ToolText.str(args, "period");
            String kind = ToolText.str(args, "kind");
            String title = ToolText.str(args, "title");
            if (scope == null || period == null || kind == null || title == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供 scope/period/kind/title（KR 还需 parentId）");
            }
            GoalView view = goals.create(new GoalRequest(scope, period, kind,
                    WorklogToolsHelper.longArg(args, "parentId"), title, ToolText.str(args, "content"),
                    args.get("weight") != null ? Integer.valueOf(ToolText.intArg(args, "weight", 0, 0, 100)) : null,
                    null, null, null));
            String text = "已创建 " + view.period() + " 的"
                    + ("KEY_RESULT".equals(view.kind()) ? " KR" : "目标") + "：" + view.title()
                    + (view.parentTitle() == null ? "" : "（挂在「" + view.parentTitle() + "」下）");
            return ToolResult.ok(text, text, ToolResult.data(
                    "id", view.id(), "period", view.period(), "kind", view.kind(),
                    "status", view.status()));
        });
    }

    @Bean
    public PlatformTool updateWorklogGoalTool() {
        ToolSpec spec = new ToolSpec("update_worklog_goal", "修改目标",
                "修改本人目标的标题/内容/权重/进度（0~100）/状态（ACTIVE/DONE/DROPPED）/父目标。"
                        + "只改传入字段，未传字段保持不变（读-合并-写）；scope/period/kind 是身份属性不可改，"
                        + "已滚转（CARRIED）目标只读。调用后生成确认单。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "id": {"type": "integer", "minimum": 1},
                            "title": {"type": "string", "maxLength": 256},
                            "content": {"type": "string", "maxLength": 20000},
                            "weight": {"type": "integer", "minimum": 0, "maximum": 100},
                            "progress": {"type": "integer", "minimum": 0, "maximum": 100},
                            "status": {"type": "string", "enum": ["ACTIVE", "DONE", "DROPPED"]},
                            "parentId": {"type": "integer", "minimum": 1}
                          },
                          "required": ["id"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            Long id = WorklogToolsHelper.longArg(args, "id");
            if (id == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供目标 id（用 list_worklog_goals 查）");
            }
            if (ToolText.str(args, "title") == null && ToolText.str(args, "content") == null
                    && args.get("weight") == null && args.get("progress") == null
                    && ToolText.str(args, "status") == null && args.get("parentId") == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "至少提供一个要修改的字段");
            }
            GoalView cur = goals.get(id);
            GoalView view = goals.update(id, new GoalRequest(cur.scope(), cur.period(), cur.kind(),
                    args.get("parentId") != null ? WorklogToolsHelper.longArg(args, "parentId") : cur.parentId(),
                    ToolText.str(args, "title") != null ? ToolText.str(args, "title") : cur.title(),
                    ToolText.str(args, "content") != null ? ToolText.str(args, "content") : cur.content(),
                    args.get("weight") != null
                            ? Integer.valueOf(ToolText.intArg(args, "weight", 0, 0, 100)) : cur.weight(),
                    args.get("progress") != null
                            ? Integer.valueOf(ToolText.intArg(args, "progress", 0, 0, 100)) : cur.progress(),
                    ToolText.str(args, "status"), null));
            String text = "已更新目标 #" + view.id() + "：" + view.title()
                    + "（" + view.status() + "，进度 " + (view.progress() == null ? 0 : view.progress()) + "%）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "id", view.id(), "status", view.status(),
                    "progress", view.progress() == null ? 0 : view.progress()));
        });
    }

    /** 渲染一个 O 节点及其 KR 行，同时收集扁平行数据（供 data 输出与模型引用 id）。 */
    private static void appendNodes(StringBuilder sb, List<Map<String, Object>> rows,
                                    List<GoalTreeView.GoalNode> nodes) {
        if (nodes.isEmpty()) {
            sb.append("（无）");
            return;
        }
        for (GoalTreeView.GoalNode node : nodes) {
            GoalView o = node.goal();
            sb.append("\n- #").append(o.id()).append(" [").append(o.status()).append("] ")
                    .append(o.title());
            if (o.progress() != null && o.progress() > 0) {
                sb.append(" · 进度 ").append(o.progress()).append("%");
            }
            rows.add(row(o));
            for (GoalView kr : node.krs()) {
                sb.append("\n  - KR #").append(kr.id()).append(" [").append(kr.status()).append("] ")
                        .append(kr.title()).append(" · 进度 ").append(kr.progress() == null ? 0 : kr.progress())
                        .append("%");
                if (kr.suggestedProgress() != null) {
                    sb.append("（计划完成度 ").append(kr.suggestedProgress()).append("%");
                    if (kr.linkedHours() != null && kr.linkedHours() > 0) {
                        sb.append("，已投入 ").append(WorklogToolsHelper.hours(kr.linkedHours())).append("h");
                    }
                    sb.append("）");
                }
                rows.add(row(kr));
            }
        }
    }

    private static Map<String, Object> row(GoalView g) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", g.id());
        row.put("scope", g.scope());
        row.put("period", g.period());
        row.put("kind", g.kind());
        row.put("title", g.title());
        row.put("status", g.status());
        if (g.parentId() != null) {
            row.put("parentId", g.parentId());
        }
        if (g.progress() != null) {
            row.put("progress", g.progress());
        }
        return row;
    }
}
