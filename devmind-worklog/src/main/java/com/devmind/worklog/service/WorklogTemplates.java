package com.devmind.worklog.service;

import java.util.Map;

/**
 * CAP-41 FR-05 报告格式模板：内置默认模板 + 占位符渲染。
 *
 * <p>占位符：{{date}}（日报日期）/ {{weekRange}}（周报周区间）/ {{entries}}（条目/日报清单）
 * / {{commits}}（git 提交清单）。用户自定义模板存 worklog_user_settings.daily_template_md
 * / weekly_template_md（空白 = 回退内置默认）。渲染发生在生成 prompt 时（服务端），
 * 渲染结果同时进会话 prompt 与上下文包 CLAUDE.md「当前任务」节。</p>
 *
 * <p>内置模板即会话 prompt 全文（不再追加协议文本），所以写作口径全部写在这里；素材清单
 * （{{entries}}/{{commits}}）由 {@link ReportPrompts} 填充。口径与团队既有日报/周报一致：
 * 有序编号 + 主题句（主题：做了什么 + 产出）、不列 commit sha、非计划事项收「计划外工作」。</p>
 */
public final class WorklogTemplates {

    /** 内置日报模板（周报分节标题与 {@link ReportService#splitWeekly} 解析约定联动，改动需同步） */
    public static final String DEFAULT_DAILY = """
            # {{date}} 工作日报

            请基于下方素材生成当日工作日报正文（Markdown，除标题外不要客套话；素材里没有的事不要编造）：

            写作要求：
            - 用有序列表（1. 2. 3.）逐条列出，一条一件事：先写主题（模块/能力/项目名），
              冒号后一到两句话说明做了什么、结果如何
            - 按主题归并：同一件事的多次提交、多条条目合成一条，不按提交或条目逐条罗列，
              正文不出现 commit sha（提交清单只作素材依据）
            - 主线任务在前；项目支持、答疑、临时插单等非计划事项编在末尾，以「项目支持：」开头
            - 全篇 3~8 条为宜；没有产出的过程性工作并入相邻条目，不要单独列「参加例会」这类流水
            - 优先保留结果、影响与量化数字（完成度、覆盖率、耗时、修复效果）

            正文末尾空一行附工时合计一行，格式：工时合计：X 小时 Y 分钟（按条目分钟数换算）。

            ## 当日 git 提交
            {{commits}}

            ## 当日工作条目
            {{entries}}
            """;

    /**
     * 内置周报模板（「## 上周总结」「## 下周计划」两小节标题是镜像落库的分节解析约定；
     * 「计划外工作」块必须落在上周总结内，否则会被 splitWeekly 切进下周计划）。
     */
    public static final String DEFAULT_WEEKLY = """
            # {{weekRange}} 工作周报

            请基于下方本周素材生成周报，正文恰好包含「## 上周总结」「## 下周计划」两个小节
            （前端按这两个标题切分落库，标题文字不可改，也不要增加其他章节）：

            ## 上周总结

            用有序列表（1. 2. 3.）逐条列出，按主题对本周日报与条目做跨天合并，不要按天罗列：
            - 每条一句话：先写做了什么（工作内容），需要时再补产出与结果（完成度、覆盖模块、量化指标）
            - 已完成的写完成，进行中的注明进度；本周没推进的事项不要写进总结
            - 项目支持、答疑、临时插单等非计划事项，另起一行加粗小标题「**计划外工作**」，
              在本节末尾单独编号列出，不与主线成果混排

            ## 下周计划

            用有序列表列出草稿，每条一句话，来源于本周未完成/进行中的线索与周期性工作
            （版本、发版、例行测试等）；不要写「继续推进」这类没有指向的空条目。

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
