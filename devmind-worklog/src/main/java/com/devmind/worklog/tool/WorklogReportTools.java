package com.devmind.worklog.tool;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.QuarterlyReportView;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.service.ReportService;
import com.devmind.worklog.service.WorklogQuarters;
import com.devmind.worklog.service.WorklogWeeks;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M9 报告工具：查日报/周报/季度复盘，手工保存日报/周报正文。
 * 数据归属当前登录账号；AI 生成（generate）不在工具内提供——它是异步 runner 会话链路，
 * 由页面/定时任务触发（CAP-76 决策：生成类异步作业不进同步工具）。
 * 更新时报告不存在会先自动创建空白报告再写入（幂等），避免"先建再改"两步确认。
 */
@Configuration
public class WorklogReportTools {

    private static final String M = "CAP-76 M9";

    private final ReportService reports;
    private final IdentityService identity;

    public WorklogReportTools(ReportService reports, IdentityService identity) {
        this.reports = reports;
        this.identity = identity;
    }

    @Bean
    public PlatformTool getDailyReportTool() {
        ToolSpec spec = new ToolSpec("get_daily_report", "查询日报",
                "查询本人某天的日报全文（markdown）。date 为 yyyy-MM-dd，缺省今天；"
                        + "该天没有日报时返回提示（不算失败）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "date": {"type": "string", "maxLength": 10}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            LocalDate date = WorklogToolsHelper.dateOr(ToolText.str(call.args(), "date"), LocalDate.now());
            DailyReportView view = reports.getDaily(identity.currentActor(), date);
            if (view == null) {
                String text = date + " 还没有日报。";
                return ToolResult.ok(text, text, ToolResult.data("exists", false, "date", date.toString()));
            }
            String summary = date + " 日报（更新于 " + ToolText.time(view.updatedAt()) + "）：\n"
                    + ToolText.truncate(view.contentMd(), 2000);
            return ToolResult.ok(summary, date + " 日报：\n\n" + view.contentMd(), ToolResult.data(
                    "exists", true, "date", date.toString(), "contentMd",
                    view.contentMd() == null ? "" : view.contentMd()));
        });
    }

    @Bean
    public PlatformTool getWeeklyReportTool() {
        ToolSpec spec = new ToolSpec("get_weekly_report", "查询周报",
                "查询本人某周的周报（本周总结 + 下周计划两节，markdown）。weekStart 为该周任意一天"
                        + "（yyyy-MM-dd），缺省本周；该周没有周报时返回提示（不算失败）。",
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
            LocalDate monday = WorklogWeeks.mondayOf(WorklogToolsHelper.dateOr(
                    ToolText.str(call.args(), "weekStart"), LocalDate.now()));
            WeeklyReportView view = reports.getWeekly(identity.currentActor(), monday);
            if (view == null) {
                String text = monday + " 这一周还没有周报。";
                return ToolResult.ok(text, text,
                        ToolResult.data("exists", false, "weekStart", monday.toString()));
            }
            String summaryMd = view.summaryMd() == null ? "" : view.summaryMd();
            String nextPlanMd = view.nextPlanMd() == null ? "" : view.nextPlanMd();
            String summary = monday + " 周报（更新于 " + ToolText.time(view.updatedAt()) + "）：\n【本周总结】\n"
                    + ToolText.truncate(summaryMd, 1500) + "\n【下周计划】\n" + ToolText.truncate(nextPlanMd, 800);
            String display = monday + " 周报：\n\n## 本周总结\n" + summaryMd + "\n\n## 下周计划\n" + nextPlanMd;
            return ToolResult.ok(summary, display, ToolResult.data(
                    "exists", true, "weekStart", monday.toString(),
                    "summaryMd", summaryMd, "nextPlanMd", nextPlanMd));
        });
    }

    @Bean
    public PlatformTool getQuarterlyReportTool() {
        ToolSpec spec = new ToolSpec("get_quarterly_report", "查询季度复盘",
                "查询本人某季度的 OKR 复盘全文（markdown）。period 形如 2026-Q1，缺省当前季度；"
                        + "该季度没有复盘时返回提示（不算失败）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "period": {"type": "string", "maxLength": 8}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            String period = ToolText.str(call.args(), "period");
            String p = period != null ? period : WorklogQuarters.quarterOf(LocalDate.now());
            QuarterlyReportView view = reports.getQuarterly(identity.currentActor(), p);
            if (view == null) {
                String text = p + " 还没有季度复盘。";
                return ToolResult.ok(text, text, ToolResult.data("exists", false, "period", p));
            }
            String summary = p + " 季度复盘（更新于 " + ToolText.time(view.updatedAt()) + "）：\n"
                    + ToolText.truncate(view.contentMd(), 2000);
            return ToolResult.ok(summary, p + " 季度复盘：\n\n" + view.contentMd(), ToolResult.data(
                    "exists", true, "period", view.quarter(), "contentMd",
                    view.contentMd() == null ? "" : view.contentMd()));
        });
    }

    @Bean
    public PlatformTool updateDailyReportTool() {
        ToolSpec spec = new ToolSpec("update_daily_report", "保存日报",
                "手工写入本人某天的日报正文（markdown，整篇覆盖）。date 为 yyyy-MM-dd，缺省今天；"
                        + "该天还没有日报时自动先建空白日报再写入。调用后生成确认单，用户确认后才落库。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "date": {"type": "string", "maxLength": 10},
                            "contentMd": {"type": "string", "maxLength": 50000}
                          },
                          "required": ["contentMd"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String content = ToolText.str(args, "contentMd");
            if (content == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供日报正文 contentMd");
            }
            LocalDate date = WorklogToolsHelper.dateOr(ToolText.str(args, "date"), LocalDate.now());
            String actor = identity.currentActor();
            reports.createDaily(actor, date);   // 幂等：不存在才建空白
            DailyReportView view = reports.updateDaily(actor, date, new UpdateDailyRequest(content, null));
            String text = "已保存 " + date + " 的日报（" + content.length() + " 字）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "date", date.toString(), "updatedAt", ToolText.time(view.updatedAt())));
        });
    }

    @Bean
    public PlatformTool updateWeeklyReportTool() {
        ToolSpec spec = new ToolSpec("update_weekly_report", "保存周报",
                "手工写入本人某周周报的「本周总结」和/或「下周计划」（markdown；只写传入的节，"
                        + "未传的节保留现状）。weekStart 为该周任意一天，缺省本周；该周还没有周报时"
                        + "自动先建空白周报再写入。调用后生成确认单，用户确认后才落库。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "weekStart": {"type": "string", "maxLength": 10},
                            "summaryMd": {"type": "string", "maxLength": 50000},
                            "nextPlanMd": {"type": "string", "maxLength": 50000}
                          }
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-worklog", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String summaryMd = ToolText.str(args, "summaryMd");
            String nextPlanMd = ToolText.str(args, "nextPlanMd");
            if (summaryMd == null && nextPlanMd == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "至少提供一节：summaryMd（本周总结）或 nextPlanMd（下周计划）");
            }
            LocalDate monday = WorklogWeeks.mondayOf(WorklogToolsHelper.dateOr(
                    ToolText.str(args, "weekStart"), LocalDate.now()));
            String actor = identity.currentActor();
            reports.createWeekly(actor, monday);   // 幂等：不存在才建空白
            WeeklyReportView view = reports.updateWeekly(actor, monday,
                    new UpdateWeeklyRequest(summaryMd, nextPlanMd, null));
            String text = "已保存 " + monday + " 周的周报（"
                    + (summaryMd != null ? "本周总结" : "") + (summaryMd != null && nextPlanMd != null ? "、" : "")
                    + (nextPlanMd != null ? "下周计划" : "") + "）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "weekStart", monday.toString(), "updatedAt", ToolText.time(view.updatedAt())));
        });
    }
}
