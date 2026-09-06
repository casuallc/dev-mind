package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogEntryEntity;

import java.time.Instant;
import java.time.LocalDate;

/** 工作条目视图：内部分钟 → 对外 hours（小时）；repoName 由列表查询批量解析（单条写入场景为 null）。 */
public record EntryView(Long id, LocalDate workDate, String title, String content,
                        String entryType, Double hours, String source,
                        Long repoId, String repoName, String commitSha,
                        String requirementId, String jiraIssueKey,
                        Instant createdAt, Instant updatedAt) {

    public static EntryView of(WorklogEntryEntity e) {
        return of(e, null);
    }

    public static EntryView of(WorklogEntryEntity e, String repoName) {
        return new EntryView(e.getId(), e.getWorkDate(), e.getTitle(), e.getContent(),
                e.getEntryType(), e.getMinutes() == null ? 0d : e.getMinutes() / 60.0,
                e.getSource(), e.getRepoId(), repoName, e.getCommitSha(),
                e.getRequirementId(), e.getJiraIssueKey(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
