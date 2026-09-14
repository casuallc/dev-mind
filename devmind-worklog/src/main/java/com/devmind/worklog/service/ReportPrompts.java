package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CAP-41 FR-03/05 生成 prompt 组装：渲染后的格式模板 + 当日/当周素材。
 *
 * <p>素材直接填进模板占位符（{{entries}}/{{commits}}），claude 在 runner 持久工作区内
 * 还能回读历史日报/周报文件，prompt 只承担「本次任务 + 素材快照」。</p>
 */
final class ReportPrompts {

    private ReportPrompts() {
    }

    static String daily(LocalDate date, List<GitCommitView> commits,
                        List<WorklogEntryEntity> entries, String template) {
        return WorklogTemplates.render(template, Map.of(
                "date", date.toString(),
                "weekRange", "",
                "entries", formatEntries(entries),
                "commits", formatCommits(commits)));
    }

    static String weekly(LocalDate weekStart, List<DailyReportEntity> dailies,
                         List<WorklogEntryEntity> entries, List<GitCommitView> commits, String template) {
        String material = !dailies.isEmpty() ? formatDailies(dailies) : formatEntries(entries);
        return WorklogTemplates.render(template, Map.of(
                "date", weekStart.toString(),
                "weekRange", weekStart + " ~ " + weekStart.plusDays(6),
                "entries", material,
                "commits", formatCommits(commits)));
    }

    static String formatCommits(List<GitCommitView> commits) {
        if (commits == null || commits.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (GitCommitView c : commits) {
            sb.append("- [").append(c.repoName()).append("] ").append(c.subject())
                    .append("（").append(c.sha(), 0, Math.min(8, c.sha().length())).append("）\n");
        }
        return sb.toString().strip();
    }

    static String formatEntries(List<WorklogEntryEntity> entries) {
        if (entries == null || entries.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (WorklogEntryEntity e : entries) {
            sb.append("- ").append(e.getWorkDate()).append(" [").append(e.getEntryType())
                    .append("] ").append(e.getTitle())
                    .append("（").append(e.getMinutes() == null ? 0 : e.getMinutes()).append(" 分钟）");
            if (e.getContent() != null && !e.getContent().isBlank()) {
                sb.append("：").append(e.getContent());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    static String formatDailies(List<DailyReportEntity> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (DailyReportEntity d : dailies) {
            sb.append("### ").append(d.getWorkDate()).append('\n')
                    .append(d.getContentMd() == null ? "" : d.getContentMd()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
