package com.devmind.worklog.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WorklogTemplates：占位符渲染、内置默认回退、未知占位符保留。 */
class WorklogTemplatesTest {

    @Test
    void 占位符全部替换() {
        String out = WorklogTemplates.render("date={{date}} range={{weekRange}}\n{{entries}}\n{{commits}}",
                Map.of("date", "2026-09-14", "weekRange", "2026-09-07 ~ 2026-09-13",
                        "entries", "- 条目A", "commits", "- [repo] 提交B"));
        assertEquals("date=2026-09-14 range=2026-09-07 ~ 2026-09-13\n- 条目A\n- [repo] 提交B", out);
    }

    @Test
    void 未知占位符原样保留() {
        String out = WorklogTemplates.render("{{unknown}} {{date}}", Map.of("date", "D"));
        assertEquals("{{unknown}} D", out);
    }

    @Test
    void 空白自定义回退内置默认() {
        assertEquals(WorklogTemplates.DEFAULT_DAILY, WorklogTemplates.orDefault(null, WorklogTemplates.DEFAULT_DAILY));
        assertEquals(WorklogTemplates.DEFAULT_DAILY, WorklogTemplates.orDefault("  ", WorklogTemplates.DEFAULT_DAILY));
        assertEquals("自定义", WorklogTemplates.orDefault("自定义", WorklogTemplates.DEFAULT_DAILY));
    }

    @Test
    void 内置默认模板含全部约定占位符() {
        for (String ph : new String[]{"{{date}}", "{{entries}}", "{{commits}}"}) {
            assertTrue(WorklogTemplates.DEFAULT_DAILY.contains(ph), "日报默认模板缺 " + ph);
        }
        for (String ph : new String[]{"{{weekRange}}", "{{entries}}", "{{commits}}"}) {
            assertTrue(WorklogTemplates.DEFAULT_WEEKLY.contains(ph), "周报默认模板缺 " + ph);
        }
        // 周报分节标题与 ReportService.splitWeekly 的镜像解析约定联动，不能漂移
        assertTrue(WorklogTemplates.DEFAULT_WEEKLY.contains(ReportService.SUMMARY_HEADING));
        assertTrue(WorklogTemplates.DEFAULT_WEEKLY.contains(ReportService.PLAN_HEADING));
        assertFalse(WorklogTemplates.DEFAULT_DAILY.contains("{{weekRange}}"), "日报模板不应含周报占位符");
    }

    @Test
    void 内置模板锁定写作口径() {
        // 口径=成稿口吻的唯一来源（模板即 prompt）：改坏会静默换风格，故钉死关键约定
        for (String rule : new String[]{"有序列表", "commit sha", "项目支持：", "工时合计"}) {
            assertTrue(WorklogTemplates.DEFAULT_DAILY.contains(rule), "日报模板缺写作约定: " + rule);
        }
        for (String rule : new String[]{"有序列表", "计划外工作", "下周计划"}) {
            assertTrue(WorklogTemplates.DEFAULT_WEEKLY.contains(rule), "周报模板缺写作约定: " + rule);
        }
    }

    @Test
    void 周报计划外工作块必须落在上周总结内() {
        // 用 lastIndexOf：模板开头交代「## 下周计划」标题约定的那句在分节指令之前，不能当分节位置
        int summary = WorklogTemplates.DEFAULT_WEEKLY.indexOf(ReportService.SUMMARY_HEADING);
        int block = WorklogTemplates.DEFAULT_WEEKLY.indexOf("计划外工作");
        int plan = WorklogTemplates.DEFAULT_WEEKLY.lastIndexOf(ReportService.PLAN_HEADING);
        assertTrue(summary < block && block < plan,
                "计划外工作块须在上周总结节内，否则会被 splitWeekly 切进下周计划");
    }
}
