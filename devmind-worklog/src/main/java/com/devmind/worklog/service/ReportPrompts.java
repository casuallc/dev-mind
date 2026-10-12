package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CAP-41 FR-03/05 生成 prompt 组装：渲染后的格式模板 + 当日/当周素材。
 *
 * <p>CAP-74：草稿区无历史文件可回读，prompt 携带的素材快照是唯一素材口径——
 * 周报素材改由服务端仓读出的日报全文（{@link DailyMaterial}）组成。
 * CAP-74 M3：条目素材从 DB 表切到仓内 day 文件（{@link WorklogEntryService.DatedEntry}）。</p>
 */
final class ReportPrompts {

    private ReportPrompts() {
    }

    /** 周报素材中的一篇日报（服务端仓读出：日期 + 全文）。 */
    record DailyMaterial(LocalDate date, String contentMd) {
    }

    static String daily(LocalDate date, List<GitCommitView> commits,
                        List<WorklogEntryService.DatedEntry> entries, String template) {
        return WorklogTemplates.render(template, Map.of(
                "date", date.toString(),
                "weekRange", "",
                "entries", formatEntries(entries),
                "commits", formatCommits(commits)));
    }

    static String weekly(LocalDate weekStart, List<DailyMaterial> dailies,
                         List<WorklogEntryService.DatedEntry> entries,
                         List<GitCommitView> commits, String template) {
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

    static String formatEntries(List<WorklogEntryService.DatedEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (WorklogEntryService.DatedEntry d : entries) {
            WorklogEntryFile.ParsedEntry e = d.entry();
            sb.append("- ").append(d.date()).append(" [").append(e.entryType())
                    .append("] ").append(e.title())
                    .append("（").append(e.minutes()).append(" 分钟）");
            if (e.body() != null && !e.body().isBlank()) {
                sb.append("：").append(e.body());
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
}
