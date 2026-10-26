package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.model.WorklogEntryEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
                List.of(entry("联调日报生成", 60)),
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
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(),
                "{{commits}}|{{entries}}", null);
        assertEquals("（无）|（无）", prompt);
    }

    @Test
    void 周报优先以日报为素材并含周区间() {
        var d = new ReportPrompts.DailyMaterial(LocalDate.of(2026, 9, 8), "- 完成登录改造");
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7),
                List.of(d), List.of(entry("不该出现", 1)), List.of(), "",
                WorklogTemplates.DEFAULT_WEEKLY, null);
        assertTrue(prompt.contains("2026-09-07 ~ 2026-09-13"), "缺周区间");
        assertTrue(prompt.contains("### 2026-09-08"), "缺日报小节");
        assertTrue(prompt.contains("- 完成登录改造"), "缺日报正文");
        assertTrue(!prompt.contains("不该出现"), "有日报时不应回退原始条目");
    }

    @Test
    void 周报无日报时回退原始条目() {
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(),
                List.of(entry("手工条目", 30)), List.of(), "", "{{entries}}", null);
        assertTrue(prompt.contains("手工条目"));
    }

    @Test
    void 周报携带上周计划并要求逐条呼应() {
        var d = new ReportPrompts.DailyMaterial(LocalDate.of(2026, 9, 8), "- 完成登录改造");
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(d), List.of(),
                List.of(), "1. 完成登录改造\n2. 补齐回归测试", WorklogTemplates.DEFAULT_WEEKLY, null);
        assertTrue(prompt.contains("上周计划跟进"), "缺上周计划跟进块");
        assertTrue(prompt.contains("完成 / 部分完成 / 未完成"), "缺完成状态约定");
        assertTrue(prompt.contains("1. 完成登录改造"), "缺上周计划原文");
        assertTrue(prompt.contains("2. 补齐回归测试"), "缺上周计划原文");
        assertFalse(prompt.contains("{{lastWeekPlan}}"), "占位符未渲染");
    }

    @Test
    void 周报无上周计划时提示省略而非编造() {
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(), List.of(),
                List.of(), null, "{{lastWeekPlan}}", null);
        assertTrue(prompt.contains("无上周计划"));
        assertFalse(prompt.contains("{{"));
    }

    @Test
    void 补充说明追加在末尾并限长() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(),
                "task", "登录改造已上线，覆盖 3 条业务线");
        assertTrue(prompt.contains("本人补充说明"));
        assertTrue(prompt.endsWith("登录改造已上线，覆盖 3 条业务线"), "补充说明应在末尾");

        String tooLong = "x".repeat(2500);
        String capped = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(),
                "task", tooLong);
        assertTrue(capped.contains("x".repeat(2000)));
        assertFalse(capped.contains("x".repeat(2001)));
    }

    @Test
    void 空白补充说明不追加() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(),
                "task", "   ");
        assertEquals("task", prompt);
    }
}
