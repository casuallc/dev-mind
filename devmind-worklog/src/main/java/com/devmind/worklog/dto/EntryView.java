package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogEntryEntity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 工作条目视图：内部分钟 → 对外 hours（小时）；repoName 由列表查询批量解析（单条写入场景为 null）。
 * CAP-89：id 保持 String uuid（CAP-74 文件时代已定契约，不再翻转）；updatedAt 恢复真实列口径
 * （文件时代条目粒度无独立更新时间，曾用 createdAt 顶替）。
 */
public record EntryView(String id, LocalDate workDate, String title, String content,
                        String entryType, Double hours, String source,
                        Long repoId, String repoName, String commitSha,
                        String requirementId, String jiraIssueKey, Long planItemId,
                        Instant createdAt, Instant updatedAt) {

    public static EntryView of(WorklogEntryEntity e, String repoName) {
        return new EntryView(e.getId(), e.getWorkDate(), e.getTitle(), e.getContent(),
                e.getEntryType(), e.getMinutes() / 60.0,
                e.getSource(), e.getRepoId(), repoName, e.getCommitSha(),
                e.getRequirementId(), e.getJiraIssueKey(), e.getPlanItemId(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
