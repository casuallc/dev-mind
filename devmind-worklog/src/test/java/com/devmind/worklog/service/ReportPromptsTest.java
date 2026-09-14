package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ReportPrompts：模板渲染 + 素材格式化（日报/周报）。 */
class ReportPromptsTest {

    private static GitCommitView commit(String repo, String subject) {
        return new GitCommitView(1L, repo, "0123456789abcdef", "n", "n@x", Instant.now(), subject, false);
    }

    private static WorklogEntryEntity entry(String title, Integer minutes) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setUserId("u1");
        e.setWorkDate(LocalDate.of(2026, 9, 14));
        e.setEntryType("DEV");
        e.setTitle(title);
        e.setMinutes(minutes);
        return e;
    }

    @Test
    void 日报模板占位符被素材填充() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14),
                List.of(commit("dev-mind", "feat: 工作日志空间")),
                List.of(entry("联调日报生成", 60)),
                WorklogTemplates.DEFAULT_DAILY);
        assertTrue(prompt.contains("2026-09-14"), "缺日期");
        assertTrue(prompt.contains("[dev-mind] feat: 工作日志空间（01234567）"), "缺提交行");
        assertTrue(prompt.contains("[DEV] 联调日报生成（60 分钟）"), "缺条目行");
        assertTrue(prompt.indexOf("{{") < 0, "存在未渲染占位符");
    }

    @Test
    void 空素材渲染为无占位说明() {
        String prompt = ReportPrompts.daily(LocalDate.of(2026, 9, 14), List.of(), List.of(),
                "{{commits}}|{{entries}}");
        assertEquals("（无）|（无）", prompt);
    }

    @Test
    void 周报优先以日报为素材并含周区间() {
        DailyReportEntity d = new DailyReportEntity();
        d.setUserId("u1");
        d.setWorkDate(LocalDate.of(2026, 9, 8));
        d.setContentMd("- 完成登录改造");
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7),
                List.of(d), List.of(entry("不该出现", 1)), List.of(), WorklogTemplates.DEFAULT_WEEKLY);
        assertTrue(prompt.contains("2026-09-07 ~ 2026-09-13"), "缺周区间");
        assertTrue(prompt.contains("### 2026-09-08"), "缺日报小节");
        assertTrue(prompt.contains("- 完成登录改造"), "缺日报正文");
        assertTrue(!prompt.contains("不该出现"), "有日报时不应回退原始条目");
    }

    @Test
    void 周报无日报时回退原始条目() {
        String prompt = ReportPrompts.weekly(LocalDate.of(2026, 9, 7), List.of(),
                List.of(entry("手工条目", 30)), List.of(), "{{entries}}");
        assertTrue(prompt.contains("手工条目"));
    }
}
