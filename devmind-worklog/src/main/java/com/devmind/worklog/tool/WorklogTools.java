package com.devmind.worklog.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.service.WorklogEntryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M6 工时/工作日志工具：按日期范围查看本人条目、登记新条目。
 * 数据归属当前登录账号（WorklogEntryService 内部走 identity.currentActor()），无需项目上下文。
 */
@Configuration
public class WorklogTools {

    private static final String M = "CAP-76 M6";
    private static final List<String> ENTRY_TYPES =
            List.of("DEV", "SUPPORT", "MEETING", "RESEARCH", "OTHER");

    private final WorklogEntryService entries;

    public WorklogTools(WorklogEntryService entries) {
        this.entries = entries;
    }

    @Bean
    public PlatformTool listWorklogEntriesTool() {
        ToolSpec spec = new ToolSpec("list_worklog_entries", "查询工作日志",
                "按日期范围查询本人工作日志条目（日期/标题/工时/类型/关联需求或 Jira）。"
                        + "from/to 为 yyyy-MM-dd，缺省取最近 7 天；keyword 按标题模糊过滤。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "from": {"type": "string", "maxLength": 10},
                            "to": {"type": "string", "maxLength": 10},
                            "keyword": {"type": "string", "maxLength": 100},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            LocalDate to = WorklogToolsHelper.dateOr(ToolText.str(args, "to"), LocalDate.now());
            LocalDate from = WorklogToolsHelper.dateOr(ToolText.str(args, "from"), to.minusDays(6));
            if (from.isAfter(to)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "from 不能晚于 to");
            }
            int limit = ToolText.limit(args);
            EntryPage page = entries.list(from, to, ToolText.str(args, "keyword"), 0, limit);
            if (page.items().isEmpty()) {
                return ToolResult.ok("该时间范围内没有匹配的工作日志。");
            }
            StringBuilder sb = new StringBuilder("共 " + page.total() + " 条，合计 "
                    + WorklogToolsHelper.hours(page.totalMinutes() / 60.0) + " 小时（显示 " + page.items().size() + " 条）：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (EntryView e : page.items()) {
                sb.append("\n- ").append(e.workDate()).append(" ").append(e.title())
                        .append(" · ").append(WorklogToolsHelper.hours(e.hours())).append("h");
                if (e.entryType() != null) {
                    sb.append(" · ").append(e.entryType());
                }
                if (e.requirementId() != null && !e.requirementId().isBlank()) {
                    sb.append(" · 需求=").append(e.requirementId());
                }
                if (e.jiraIssueKey() != null && !e.jiraIssueKey().isBlank()) {
                    sb.append(" · Jira=").append(e.jiraIssueKey());
                }
                if (e.content() != null && !e.content().isBlank()) {
                    sb.append("\n  ").append(ToolText.oneLine(e.content(), 160));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.id());
                row.put("workDate", String.valueOf(e.workDate()));
                row.put("title", e.title());
                row.put("hours", e.hours());
                if (e.entryType() != null) {
                    row.put("entryType", e.entryType());
                }
                if (e.requirementId() != null && !e.requirementId().isBlank()) {
                    row.put("requirementId", e.requirementId());
                }
                if (e.jiraIssueKey() != null && !e.jiraIssueKey().isBlank()) {
                    row.put("jiraIssueKey", e.jiraIssueKey());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("total", page.total(),
                            "totalMinutes", page.totalMinutes(), "entries", rows));
        });
    }

    @Bean
    public PlatformTool createWorklogEntryTool() {
        ToolSpec spec = new ToolSpec("create_worklog_entry", "登记工作日志",
                "为本人登记一条工作日志（workDate 缺省今天，hours 以小时计，支持 0.25 步进）。"
                        + "entryType 可选 DEV/SUPPORT/MEETING/RESEARCH/OTHER；可关联需求或 Jira issue。"
                        + "调用后生成确认单，用户确认后才落库。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "workDate": {"type": "string", "maxLength": 10},
                            "title": {"type": "string", "maxLength": 256},
                            "content": {"type": "string", "maxLength": 20000},
                            "entryType": {"type": "string",
                                          "enum": ["DEV", "SUPPORT", "MEETING", "RESEARCH", "OTHER"]},
                            "hours": {"type": "number", "minimum": 0, "maximum": 24},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "jiraIssueKey": {"type": "string", "maxLength": 64}
                          },
                          "required": ["title", "hours"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String title = ToolText.str(args, "title");
            Object hoursArg = args.get("hours");
            if (title == null || !(hoursArg instanceof Number hoursNum)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供标题与工时（小时）");
            }
            double hours = hoursNum.doubleValue();
            if (hours <= 0 || hours > 24) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "工时必须大于 0 且不超过 24 小时");
            }
            String type = normalizeType(ToolText.str(args, "entryType"));
            LocalDate workDate = WorklogToolsHelper.dateOr(ToolText.str(args, "workDate"), LocalDate.now());
            EntryView view = entries.create(new EntryRequest(
                    workDate, title, ToolText.str(args, "content"), type, hours,
                    ToolText.str(args, "requirementId"), ToolText.str(args, "jiraIssueKey"), null,
                    null, null));
            String text = "已登记 " + view.workDate() + " 的工作日志：" + view.title()
                    + "（" + WorklogToolsHelper.hours(view.hours()) + " 小时）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "id", view.id(), "workDate", String.valueOf(view.workDate()),
                    "hours", view.hours()));
        });
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        String s = type.trim().toUpperCase(Locale.ROOT);
        if (!ENTRY_TYPES.contains(s)) {
            throw new IllegalArgumentException("entryType 仅支持 " + String.join("/", ENTRY_TYPES));
        }
        return s;
    }

}
