package com.devmind.worklog.dto;

import com.devmind.worklog.service.WorklogEntryFile;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 工作条目视图：内部分钟 → 对外 hours（小时）；repoName 由列表查询批量解析（单条写入场景为 null）。
 * CAP-74 M3：id 从 DB 自增 Long 改为仓文件 uuid 字符串；updatedAt 口径 = createdAt
 * （条目粒度无独立更新时间，文件级历史走 git log）。
 */
public record EntryView(String id, LocalDate workDate, String title, String content,
                        String entryType, Double hours, String source,
                        Long repoId, String repoName, String commitSha,
                        String requirementId, String jiraIssueKey,
                        Instant createdAt, Instant updatedAt) {

    public static EntryView of(LocalDate workDate, WorklogEntryFile.ParsedEntry e, String repoName) {
        return new EntryView(e.id(), workDate, e.title(), e.body(),
                e.entryType(), e.minutes() / 60.0,
                e.source(), e.repoId(), repoName, e.commitSha(),
                e.requirementId(), e.jiraIssueKey(),
                e.createdAt(), e.createdAt());
    }
}
