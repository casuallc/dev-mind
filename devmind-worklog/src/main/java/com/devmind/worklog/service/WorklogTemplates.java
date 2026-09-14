package com.devmind.worklog.service;

import java.util.Map;

/**
 * CAP-41 FR-05 报告格式模板：内置默认模板 + 占位符渲染。
 *
 * <p>占位符：{{date}}（日报日期）/ {{weekRange}}（周报周区间）/ {{entries}}（条目/日报清单）
 * / {{commits}}（git 提交清单）。用户自定义模板存 worklog_user_settings.daily_template_md
 * / weekly_template_md（空白 = 回退内置默认）。渲染发生在生成 prompt 时（服务端），
 * 渲染结果同时进会话 prompt 与上下文包 CLAUDE.md「当前任务」节。</p>
 */
public final class WorklogTemplates {

    /** 内置日报模板（周报分节标题与 {@link ReportService#splitWeekly} 解析约定联动，改动需同步） */
    public static final String DEFAULT_DAILY = """
            # {{date}} 工作日报

            请基于下方素材生成当日工作日志正文（Markdown，不要标题外的客套话）：
            - 按主题归并同类提交与条目，每条一句话说明做了什么、结果如何
            - 文末附一行工时合计

            ## 当日 git 提交
            {{commits}}

            ## 当日工作条目
            {{entries}}
            """;

    /** 内置周报模板（「## 上周总结」「## 下周计划」两小节标题是镜像落库的分节解析约定） */
    public static final String DEFAULT_WEEKLY = """
            # {{weekRange}} 工作周报

            请基于下方本周素材生成周报，正文恰好包含「## 上周总结」「## 下周计划」两个小节：
            - 上周总结：按主题归并，每条一句话，突出成果与进展
            - 下周计划：根据本周未完成/进行中的线索列草稿，每条一句话

            ## 本周日报与条目
            {{entries}}

            ## 本周 git 提交
            {{commits}}
            """;

    private WorklogTemplates() {
    }

    /** 用户自定义模板（null/空白 → 内置默认）。 */
    public static String orDefault(String custom, String builtin) {
        return custom == null || custom.isBlank() ? builtin : custom;
    }

    /** 纯字符串替换渲染；未知占位符原样保留（用户写了自己的占位不报错，便于排查）。 */
    public static String render(String template, Map<String, String> vars) {
        String out = template == null ? "" : template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
