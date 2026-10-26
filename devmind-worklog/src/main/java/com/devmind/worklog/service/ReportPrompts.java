package com.devmind.worklog.service;

import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-41 FR-03/05 生成 prompt 组装：渲染后的格式模板 + 当日/当周素材。
 *
 * <p>CAP-74：草稿区无历史文件可回读，prompt 携带的素材快照是唯一素材口径——
 * 周报素材由日报全文（{@link DailyMaterial}）组成。CAP-89：存储回 DB，素材口径不变
 * （条目/日报均从 DB 表读，{@link WorklogEntryService.DatedEntry}）。</p>
 *
 * <p>生成质量：git 素材按仓库分组，带提交正文与改动规模（同一需求的多次提交才可归并）；
 * 条目补需求/Jira/来源；CAP-88 M2：日报增「今日计划对照」块（当日日计划 + 当周周计划 +
 * 真实状态 + 关联条目），周报「上周计划」由计划项结构化清单驱动（无计划项回退上周周报
 * next_plan_md 文本）并增「本周已排计划项」；本人补充说明追加在末尾（高优先级事实，
 * 与素材冲突时以它为准）。</p>
 */
final class ReportPrompts {

    private static final int GIT_BODY_CHARS = 300;
    private static final int ENTRY_BODY_CHARS = 1200;
    private static final int HINT_CHARS = 2000;
    private static final DateTimeFormatter MONTH_DAY =
            DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault());

    private ReportPrompts() {
    }

    /** 周报素材中的一篇日报（服务端仓读出：日期 + 全文）。 */
    record DailyMaterial(LocalDate date, String contentMd) {
    }

    static String daily(LocalDate date, List<GitCommitView> commits,
                        List<WorklogEntryService.DatedEntry> entries, String plans,
                        String template, String hint) {
        return appendHint(WorklogTemplates.render(template, Map.of(
                "date", date.toString(),
                "weekRange", "",
                "entries", formatEntries(entries),
                "commits", formatCommits(commits),
                "plans", plans == null ? formatPlanItems(null, null) : plans)), hint);
    }

    static String weekly(LocalDate weekStart, List<DailyMaterial> dailies,
                         List<WorklogEntryService.DatedEntry> entries,
                         List<GitCommitView> commits, String lastWeekPlan, String thisWeekPlan,
                         String template, String hint) {
        String material = !dailies.isEmpty() ? formatDailies(dailies) : formatEntries(entries);
        return appendHint(WorklogTemplates.render(template, Map.of(
                "date", weekStart.toString(),
                "weekRange", weekStart + " ~ " + weekStart.plusDays(6),
                "entries", material,
                "commits", formatCommits(commits),
                "lastWeekPlan", formatLastWeekPlan(lastWeekPlan),
                "thisWeekPlan", thisWeekPlan == null || thisWeekPlan.isBlank()
                        ? "（本周未排计划项：「下周计划」节依据本周未完成/进行中线索自由发挥）"
                        : thisWeekPlan)), hint);
    }

    /** 上一周周报的「下周计划」：缺省时明确提示省略跟进块，避免模型编造上周计划。 */
    static String formatLastWeekPlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return "（无上周计划：省略「上周计划跟进」块，不得编造上周计划）";
        }
        return plan.strip();
    }

    /** 状态码 → 中文标签（prompt 是中文叙述，状态直给中文，模型照抄即「以 status 为准」） */
    private static final Map<String, String> PLAN_STATUS_LABELS = Map.of(
            WorklogPlanItemEntity.STATUS_TODO, "待办",
            WorklogPlanItemEntity.STATUS_DOING, "进行中",
            WorklogPlanItemEntity.STATUS_DONE, "已完成",
            WorklogPlanItemEntity.STATUS_DROPPED, "已放弃",
            WorklogPlanItemEntity.STATUS_CARRIED, "已滚转");

    /**
     * CAP-88 M2 计划对照素材：计划项 + 真实状态 + 关联条目摘要，逐项一行。
     * 空列表渲染为「省略」指令（FR-09 存量兼容：无计划项时对照块整块省略，不编造）。
     */
    static String formatPlanItems(List<WorklogPlanItemEntity> items,
                                  Map<Long, List<WorklogEntryEntity>> linkedByPlanId) {
        if (items == null || items.isEmpty()) {
            return "（无计划项：省略计划对照/跟进块，不得编造计划）";
        }
        StringBuilder sb = new StringBuilder();
        for (WorklogPlanItemEntity p : items) {
            sb.append("- [").append(WorklogPlanItemEntity.SCOPE_WEEK.equals(p.getScope())
                            ? "周计划" : "日计划 " + p.getPlanDate()).append("] ")
                    .append("[状态=").append(PLAN_STATUS_LABELS.getOrDefault(p.getStatus(), p.getStatus()))
                    .append("] ").append(p.getTitle());
            List<String> meta = new ArrayList<>();
            meta.add("类型=" + p.getPlanType());
            if (p.getEstimateMinutes() != null) {
                double h = p.getEstimateMinutes() / 60.0;
                meta.add("预估=" + (h == Math.floor(h) && !Double.isInfinite(h)
                        ? Long.toString((long) h)
                        : String.format(java.util.Locale.ROOT, "%.2f", h)) + "h");
            }
            if (p.getRequirementId() != null && !p.getRequirementId().isBlank()) {
                meta.add("需求=" + p.getRequirementId());
            }
            if (p.getJiraIssueKey() != null && !p.getJiraIssueKey().isBlank()) {
                meta.add("Jira=" + p.getJiraIssueKey());
            }
            sb.append("（").append(String.join(" · ", meta)).append("）\n");
            List<WorklogEntryEntity> linked = linkedByPlanId == null ? null : linkedByPlanId.get(p.getId());
            if (linked != null && !linked.isEmpty()) {
                sb.append("  关联条目：");
                for (int i = 0; i < linked.size(); i++) {
                    WorklogEntryEntity e = linked.get(i);
                    if (i > 0) {
                        sb.append("；");
                    }
                    sb.append(e.getTitle()).append("（").append(e.getWorkDate()).append(' ')
                            .append(e.getMinutes()).append(" 分钟）");
                }
                sb.append('\n');
            } else {
                sb.append("  关联条目：无（尚无执行记录）\n");
            }
        }
        return sb.toString().strip();
    }

    /** 按仓库分组；每条带日期、改动规模与提交正文（正文压成一行，避免挤占 prompt）。 */
    static String formatCommits(List<GitCommitView> commits) {
        if (commits == null || commits.isEmpty()) {
            return "（无）";
        }
        Map<String, List<GitCommitView>> byRepo = new LinkedHashMap<>();
        for (GitCommitView c : commits) {
            String repo = c.repoName() == null || c.repoName().isBlank() ? "（未知仓库）" : c.repoName();
            byRepo.computeIfAbsent(repo, k -> new ArrayList<>()).add(c);
        }
        StringBuilder sb = new StringBuilder();
        byRepo.forEach((repo, list) -> {
            int files = list.stream().mapToInt(GitCommitView::filesChanged).sum();
            int insertions = list.stream().mapToInt(GitCommitView::insertions).sum();
            int deletions = list.stream().mapToInt(GitCommitView::deletions).sum();
            sb.append("#### [").append(repo).append("] ").append(list.size()).append(" 个提交");
            if (files > 0) {
                sb.append("，").append(files).append(" 个文件，+").append(insertions)
                        .append("/-").append(deletions);
            }
            sb.append('\n');
            for (GitCommitView c : list) {
                sb.append("- ").append(MONTH_DAY.format(c.committedAt())).append(' ')
                        .append(c.subject() == null ? "" : c.subject());
                if (c.filesChanged() > 0) {
                    sb.append("（+").append(c.insertions()).append("/-").append(c.deletions()).append("）");
                }
                sb.append('\n');
                String body = oneLine(c.body(), GIT_BODY_CHARS);
                if (!body.isEmpty()) {
                    sb.append("  ").append(body).append('\n');
                }
            }
        });
        return sb.toString().strip();
    }

    /** 条目带类型、工时、需求/Jira 关联与来源；正文压成一行并限长。 */
    static String formatEntries(List<WorklogEntryService.DatedEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (WorklogEntryService.DatedEntry d : entries) {
            WorklogEntryEntity e = d.entry();
            List<String> meta = new ArrayList<>();
            meta.add(e.getMinutes() + " 分钟");
            if (e.getRequirementId() != null && !e.getRequirementId().isBlank()) {
                meta.add("需求=" + e.getRequirementId());
            }
            if (e.getJiraIssueKey() != null && !e.getJiraIssueKey().isBlank()) {
                meta.add("Jira=" + e.getJiraIssueKey());
            }
            if (e.getSource() != null && !e.getSource().isBlank()) {
                meta.add("来源=" + e.getSource());
            }
            sb.append("- ").append(d.date()).append(" [").append(e.getEntryType()).append("] ")
                    .append(e.getTitle())
                    .append("（").append(String.join(" · ", meta)).append("）");
            String body = oneLine(e.getContent(), ENTRY_BODY_CHARS);
            if (!body.isEmpty()) {
                sb.append("：").append(body);
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    static String formatDailies(List<DailyMaterial> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (DailyMaterial d : dailies) {
            sb.append("### ").append(d.date()).append('\n')
                    .append(d.contentMd() == null ? "" : d.contentMd()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * CAP-88 M3 FR-10「AI 建议下周计划」prompt：目标周 + 素材（上周未完成/滚转项 +
     * 在办需求，缺席降级为空提示）+ 产物格式契约（与场景骨架、{@code PlanSuggestParser}
     * 三方共用行格式 `- [类型] 标题（预估 Xh）`，改动必须三处同步）。
     */
    static String planSuggest(LocalDate targetWeekStart, String lastWeekItems, String wipRequirements, String hint) {
        String prompt = "请为我建议 " + targetWeekStart + " 周（" + targetWeekStart + " ~ "
                + targetWeekStart.plusDays(6) + "）的工作计划。\n\n"
                + "## 素材：上周未完成/已滚转的计划项（优先承接，已完成的不要重复建议）\n\n"
                + (lastWeekItems == null || lastWeekItems.isBlank()
                        ? "（上周无未完成计划项）" : lastWeekItems) + "\n\n"
                + "## 素材：当前在办需求（可作为计划方向来源）\n\n"
                + (wipRequirements == null || wipRequirements.isBlank()
                        ? "（无在办需求数据）" : wipRequirements) + "\n\n"
                + "## 输出契约（严格遵守，平台按行解析）\n\n"
                + "- 3~6 条建议，每条一行：`- [类型] 标题（预估 Xh）`\n"
                + "- 类型 ∈ DEV/SUPPORT/MEETING/RESEARCH/OTHER；预估工时可省略\n"
                + "- 每条建议必须能从上方素材追溯（承接未完成项或推进在办需求），不要编造与素材无关的空话\n"
                + "- 除建议行外不写任何其他内容";
        return appendHint(prompt, hint);
    }

    /** 在办需求素材（CAP-88 M3：SPI 探测命中时注入；空列表渲染「无数据」提示）。 */
    static String formatWipRequirements(List<WipRequirementProvider.WipRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return "（无在办需求数据）";
        }
        StringBuilder sb = new StringBuilder();
        for (WipRequirementProvider.WipRequirement r : requirements) {
            sb.append("- ").append(r.title())
                    .append("（").append(r.requirementId())
                    .append(" · 状态=").append(r.status());
            if (r.projectName() != null && !r.projectName().isBlank()) {
                sb.append(" · 项目=").append(r.projectName());
            }
            sb.append("）\n");
        }
        return sb.toString().strip();
    }

    /** 本人补充说明：只有用户填了才追加；放在末尾让模型最后读到，冲突时以它为准。 */
    static String appendHint(String prompt, String hint) {        String h = hint == null ? "" : hint.strip();
        if (h.isEmpty()) {
            return prompt;
        }
        if (h.length() > HINT_CHARS) {
            h = h.substring(0, HINT_CHARS);
        }
        return prompt + "\n\n### 本人补充说明（高优先级事实，必须体现；与素材冲突时以此为准）\n\n" + h;
    }

    private static String oneLine(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.replaceAll("\\s+", " ").strip();
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
    }
}
