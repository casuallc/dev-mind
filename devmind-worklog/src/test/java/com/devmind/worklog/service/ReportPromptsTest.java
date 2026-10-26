package com.devmind.worklog.service;

import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ReportPrompts：模板渲染 + 素材格式化（日报/周报）+ 补充说明注入。 */
class ReportPromptsTest {

    private static GitCommitView commit(String repo, String subject) {
        return commit(repo, subject, "", 1, 1, 0);
    }

    private static GitCommitView commit(String repo, String subject, String body,
                                        int filesChanged, int insertions, int deletions) {
        return new GitCommitView(1L, repo, "0123456789abcdef", "n", "n@x",
                Instant.parse("2026-09-14T02:00:00Z"), subject, body,
                filesChanged, insertions, deletions, false);
    }

    private static WorklogEntryService.DatedEntry entry(String title, int minutes) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setId("id1");
        e.setWorkDate(LocalDate.of(2026, 9, 14));
        e.setTitle(title);
        e.setEntryType("DEV");
        e.setMinutes(minutes);
        e.setSource("MANUAL");
        return new WorklogEntryService.DatedEntry(LocalDate.of(2026, 9, 14), e);
    }

    @Test
    void 日报模板占位符被素材填充() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14),
                List.of(commit("dev-mind", "feat: 工作日志空间", "补齐素材正文与改动规模", 2, 10, 3)),
                List.of(entry("联调日报生成", 60)), null,
                WorklogTemplates.DEFAULT_DAILY, null);
        assertTrue(prompt.contains("2026-09-14"), "缺日期");
        assertTrue(prompt.contains("[dev-mind] 1 个提交，2 个文件，+10/-3"), "缺分组或改动规模");
        assertTrue(prompt.contains("补齐素材正文与改动规模"), "缺提交正文");
        assertTrue(prompt.contains("[DEV] 联调日报生成（60 分钟"), "缺条目行");
        assertFalse(prompt.contains("01234567"), "素材不应携带 commit sha");
        assertTrue(prompt.indexOf("{{") < 0, "存在未渲染占位符");
    }

    @Test
    void 空素材渲染为无占位说明() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(), null,
                "{{commits}}|{{entries}}|{{plans}}", null);
        assertEquals("（无）|（无）|（无计划项：省略计划对照/跟进块，不得编造计划）", prompt);
    }

    @Test
    void 周报优先以日报为素材并含周区间() {
        var d = new ReportPrompts.DailyMaterial(LocalDate.of(2026, 9, 8), "- 完成登录改造");
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7),
                List.of(d), List.of(entry("不该出现", 1)), List.of(), "", "", "",
                WorklogTemplates.DEFAULT_WEEKLY, null);
        assertTrue(prompt.contains("2026-09-07 ~ 2026-09-13"), "缺周区间");
        assertTrue(prompt.contains("### 2026-09-08"), "缺日报小节");
        assertTrue(prompt.contains("- 完成登录改造"), "缺日报正文");
        assertTrue(!prompt.contains("不该出现"), "有日报时不应回退原始条目");
    }

    @Test
    void 周报无日报时回退原始条目() {
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(),
                List.of(entry("手工条目", 30)), List.of(), "", "", "", "{{entries}}", null);
        assertTrue(prompt.contains("手工条目"));
    }

    @Test
    void 周报携带上周计划并要求逐条呼应() {
        var d = new ReportPrompts.DailyMaterial(LocalDate.of(2026, 9, 8), "- 完成登录改造");
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(d), List.of(),
                List.of(), "1. 完成登录改造\n2. 补齐回归测试", "", "", WorklogTemplates.DEFAULT_WEEKLY, null);
        assertTrue(prompt.contains("上周计划跟进"), "缺上周计划跟进块");
        assertTrue(prompt.contains("完成 / 部分完成 / 未完成"), "缺完成状态约定");
        assertTrue(prompt.contains("1. 完成登录改造"), "缺上周计划原文");
        assertTrue(prompt.contains("2. 补齐回归测试"), "缺上周计划原文");
        assertFalse(prompt.contains("{{lastWeekPlan}}"), "占位符未渲染");
    }

    @Test
    void 周报无上周计划时提示省略而非编造() {
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(), List.of(),
                List.of(), null, "", "", "{{lastWeekPlan}}", null);
        assertTrue(prompt.contains("无上周计划"));
        assertFalse(prompt.contains("{{"));
    }

    @Test
    void 补充说明追加在末尾并限长() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(), null,
                "task", "登录改造已上线，覆盖 3 条业务线");
        assertTrue(prompt.contains("本人补充说明"));
        assertTrue(prompt.endsWith("登录改造已上线，覆盖 3 条业务线"), "补充说明应在末尾");

        String tooLong = "x".repeat(2500);
        String capped = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(), null,
                "task", tooLong);
        assertTrue(capped.contains("x".repeat(2000)));
        assertFalse(capped.contains("x".repeat(2001)));
    }

    @Test
    void 空白补充说明不追加() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(), null,
                "task", "   ");
        assertEquals("task", prompt);
    }

    private static WorklogPlanItemEntity plan(long id, String scope, LocalDate date,
                                              String title, String status, Integer estimateMinutes) {
        WorklogPlanItemEntity p = new WorklogPlanItemEntity();
        p.setId(id);
        p.setScope(scope);
        p.setPlanDate(date);
        p.setTitle(title);
        p.setPlanType("DEV");
        p.setStatus(status);
        p.setEstimateMinutes(estimateMinutes);
        return p;
    }

    @Test
    void 计划对照块含状态与关联条目() {
        WorklogPlanItemEntity day = plan(1L, "DAY", LocalDate.of(2026, 9, 14), "写 CAP-88 设计", "DOING", 120);
        WorklogPlanItemEntity week = plan(2L, "WEEK", LocalDate.of(2026, 9, 14), "本周方向", "TODO", null);
        WorklogEntryEntity linked = entry("执行条目", 60).entry();
        String block = ReportPrompts.formatPlanItems(List.of(day, week), Map.of(1L, List.of(linked)));
        assertTrue(block.contains("[日计划 2026-09-14] [状态=进行中] 写 CAP-88 设计"), "缺日计划行: " + block);
        assertTrue(block.contains("预估=2h"), "缺预估工时");
        assertTrue(block.contains("关联条目：执行条目（2026-09-14 60 分钟）"), "缺关联条目摘要");
        assertTrue(block.contains("[周计划] [状态=待办] 本周方向"), "缺周计划行");
        assertTrue(block.contains("关联条目：无（尚无执行记录）"), "无关联条目须显式标注");
    }

    @Test
    void 计划对照空列表渲染省略指令() {
        assertEquals("（无计划项：省略计划对照/跟进块，不得编造计划）",
                ReportPrompts.formatPlanItems(List.of(), Map.of()));
        assertEquals("（无计划项：省略计划对照/跟进块，不得编造计划）",
                ReportPrompts.formatPlanItems(null, null));
    }

    @Test
    void 日报默认模板渲染今日计划对照块() {
        WorklogPlanItemEntity day = plan(1L, "DAY", LocalDate.of(2026, 9, 14), "写 CAP-88 设计", "DONE", null);
        String plans = ReportPrompts.formatPlanItems(List.of(day), Map.of());
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(), plans,
                WorklogTemplates.DEFAULT_DAILY, null);
        assertTrue(prompt.contains("今日计划对照"), "缺计划对照块");
        assertTrue(prompt.contains("[状态=已完成] 写 CAP-88 设计"), "缺计划项行");
        assertTrue(prompt.indexOf("{{") < 0, "存在未渲染占位符");
    }

    @Test
    void 周报默认模板渲染本周计划项块() {
        var d = new ReportPrompts.DailyMaterial(LocalDate.of(2026, 9, 8), "- 完成登录改造");
        WorklogPlanItemEntity thisWeek = plan(3L, "WEEK", LocalDate.of(2026, 9, 7), "收尾登录改造", "TODO", null);
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(d), List.of(), List.of(),
                "1. 完成登录改造", ReportPrompts.formatPlanItems(List.of(thisWeek), Map.of()),
                "1. [DEV] 评审收尾", WorklogTemplates.DEFAULT_WEEKLY, null);
        assertTrue(prompt.contains("本周已排计划项"), "缺本周计划项块");
        assertTrue(prompt.contains("[状态=待办] 收尾登录改造"), "缺本周计划项行");
        assertTrue(prompt.indexOf("{{") < 0, "存在未渲染占位符");
    }

    // ---------------- CAP-90 FR-04/09/10：日计划并入条目后的素材口径 ----------------

    @Test
    void 日计划对照块由未完成条目与周计划组成() {
        WorklogEntryEntity todo = entry("修首页样式", 0).entry();
        todo.setStatus("TODO");
        todo.setEstimateMinutes(120);
        WorklogEntryEntity doing = entry("联调日报生成", 0).entry();
        doing.setStatus("DOING");
        WorklogPlanItemEntity week = plan(2L, "WEEK", LocalDate.of(2026, 9, 14), "本周方向", "TODO", null);

        String block = ReportPrompts.formatDayPlanBlock(List.of(todo, doing), List.of(week), Map.of());
        assertTrue(block.contains("当日未完成条目（计划态，即今日计划）："), "缺未完成条目小节: " + block);
        assertTrue(block.contains("- [状态=待办] 修首页样式（类型=DEV · 预估=2h）"), "缺条目行: " + block);
        assertTrue(block.contains("- [状态=进行中] 联调日报生成"), "缺条目行: " + block);
        assertTrue(block.contains("当周周计划项："), "缺周计划小节: " + block);
        assertTrue(block.contains("[状态=待办] 本周方向"), "缺周计划行: " + block);
    }

    @Test
    void 日计划对照块两者皆空渲染省略指令() {
        assertEquals("（无计划项：省略计划对照/跟进块，不得编造计划）",
                ReportPrompts.formatDayPlanBlock(List.of(), List.of(), Map.of()));
        assertEquals("（无计划项：省略计划对照/跟进块，不得编造计划）",
                ReportPrompts.formatDayPlanBlock(null, null, null));
        // 只有未完成条目时也能成块（无周计划不阻断）
        WorklogEntryEntity todo = entry("修首页样式", 0).entry();
        todo.setStatus("TODO");
        String block = ReportPrompts.formatDayPlanBlock(List.of(todo), List.of(), Map.of());
        assertTrue(block.contains("当日未完成条目"), "仅未完成条目须成块: " + block);
        assertFalse(block.contains("当周周计划项"), "无周计划项不出小节: " + block);
    }

    @Test
    void 下周计划项确定性渲染为markdown() {
        WorklogPlanItemEntity p1 = plan(1L, "WEEK", LocalDate.of(2026, 9, 21), "完成周报链路联调", "TODO", 120);
        p1.setRequirementId("REQ-9");
        WorklogPlanItemEntity p2 = plan(2L, "WEEK", LocalDate.of(2026, 9, 21), "评审对齐", "TODO", null);
        p2.setPlanType("MEETING");
        p2.setJiraIssueKey("ADM-12");

        String md = ReportPrompts.renderNextPlanMarkdown(List.of(p1, p2));
        assertEquals("1. [DEV] 完成周报链路联调（预估 2h · 需求=REQ-9）\n"
                + "2. [MEETING] 评审对齐（Jira=ADM-12）", md);
        assertEquals("", ReportPrompts.renderNextPlanMarkdown(List.of()));
        assertEquals("", ReportPrompts.renderNextPlanMarkdown(null));
    }

    @Test
    void 周报下周计划素材注入与缺席降级() {
        String withItems = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(), List.of(),
                List.of(), "", "", "1. [DEV] 评审收尾", "{{nextWeekPlan}}", null);
        assertEquals("1. [DEV] 评审收尾", withItems);

        String blank = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(), List.of(),
                List.of(), "", "", "", "{{nextWeekPlan}}", null);
        assertTrue(blank.contains("无下周计划项"), blank);
        assertFalse(blank.contains("{{"));
    }

    // ---------------- CAP-88 M3：AI 建议计划 prompt ----------------

    @Test
    void 建议计划prompt携带目标周素材与输出契约() {
        String lastWeek = ReportPrompts.formatPlanItems(List.of(
                plan(1L, "WEEK", LocalDate.of(2026, 10, 19), "收尾登录改造", "TODO", null)), Map.of());
        String wip = ReportPrompts.formatWipRequirements(List.of(
                new WipRequirementProvider.WipRequirement("REQ-88", "工作日志计划闭环", "IN_PROGRESS", "dev-mind")));
        String prompt = ReportPrompts.planSuggest(LocalDate.of(2026, 10, 26), lastWeek, wip, "下周请优先评审");
        assertTrue(prompt.contains("2026-10-26 周（2026-10-26 ~ 2026-11-01）"), "缺目标周区间: " + prompt);
        assertTrue(prompt.contains("[状态=待办] 收尾登录改造"), "缺上周未完成素材");
        assertTrue(prompt.contains("REQ-88") && prompt.contains("工作日志计划闭环"), "缺在办需求素材");
        assertTrue(prompt.contains("3~6 条") && prompt.contains("- [类型] 标题（预估 Xh）"), "缺输出契约");
        assertTrue(prompt.contains("本人补充说明") && prompt.contains("下周请优先评审"), "hint 未注入");
    }

    @Test
    void 建议计划素材缺席降级为空提示() {
        String prompt = ReportPrompts.planSuggest(LocalDate.of(2026, 10, 26), "", "", null);
        assertTrue(prompt.contains("（上周无未完成计划项）"));
        assertTrue(prompt.contains("（无在办需求数据）"));
        assertFalse(prompt.contains("本人补充说明"), "空 hint 不注入补充块");
    }

    @Test
    void 在办需求项目名缺失不阻断渲染() {
        String block = ReportPrompts.formatWipRequirements(List.of(
                new WipRequirementProvider.WipRequirement("REQ-1", "标题", "ACCEPTANCE", null)));
        assertTrue(block.contains("- 标题（REQ-1 · 状态=ACCEPTANCE）"), "项目名缺失时省略项目段: " + block);
        assertEquals("（无在办需求数据）", ReportPrompts.formatWipRequirements(List.of()));
    }
}
