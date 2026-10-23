package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;

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
 * 周报素材改由服务端仓读出的日报全文（{@link DailyMaterial}）组成。
 * CAP-74 M3：条目素材从 DB 表切到仓内 day 文件（{@link WorklogEntryService.DatedEntry}）。</p>
 *
 * <p>生成质量：git 素材按仓库分组，带提交正文与改动规模（同一需求的多次提交才可归并）；
 * 条目补需求/Jira/来源；本人补充说明追加在末尾（高优先级事实，与素材冲突时以它为准）。</p>
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
                        List<WorklogEntryService.DatedEntry> entries, String template, String hint) {
        return appendHint(WorklogTemplates.render(template, Map.of(
                "date", date.toString(),
                "weekRange", "",
                "entries", formatEntries(entries),
                "commits", formatCommits(commits))), hint);
    }

    static String weekly(LocalDate weekStart, List<DailyMaterial> dailies,
                         List<WorklogEntryService.DatedEntry> entries,
                         List<GitCommitView> commits, String template, String hint) {
        String material = !dailies.isEmpty() ? formatDailies(dailies) : formatEntries(entries);
        return appendHint(WorklogTemplates.render(template, Map.of(
                "date", weekStart.toString(),
                "weekRange", weekStart + " ~ " + weekStart.plusDays(6),
                "entries", material,
                "commits", formatCommits(commits))), hint);
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
            WorklogEntryFile.ParsedEntry e = d.entry();
            List<String> meta = new ArrayList<>();
            meta.add(e.minutes() + " 分钟");
            if (e.requirementId() != null && !e.requirementId().isBlank()) {
                meta.add("需求=" + e.requirementId());
            }
            if (e.jiraIssueKey() != null && !e.jiraIssueKey().isBlank()) {
                meta.add("Jira=" + e.jiraIssueKey());
            }
            if (e.source() != null && !e.source().isBlank()) {
                meta.add("来源=" + e.source());
            }
            sb.append("- ").append(d.date()).append(" [").append(e.entryType()).append("] ")
                    .append(e.title())
                    .append("（").append(String.join(" · ", meta)).append("）");
            String body = oneLine(e.body(), ENTRY_BODY_CHARS);
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

    /** 本人补充说明：只有用户填了才追加；放在末尾让模型最后读到，冲突时以它为准。 */
    static String appendHint(String prompt, String hint) {
        String h = hint == null ? "" : hint.strip();
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
