package com.devmind.project.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Jira 同步专用字段包（托管字段）：同步通道 createFromJira/syncFromJira 使用，
 * 避免 integration 模块的 JiraIssue 类型渗入 project。
 * 托管字段 = title/description/type/priority/assignee/reporter/labels/fixVersions/dueDate/externalKey
 * 及 issue 自身的创建/更新时间（落需求 createdAt/updatedAt，保持与 Jira 一致）
 * 与工时字段（estimatedSeconds/spentSeconds，CAP-27，秒），
 * 本地字段（status/ownerId/docId）同步绝不动。
 */
public record JiraManagedFields(
        String title,
        String description,
        String type,
        String priority,
        String assignee,
        String reporter,
        List<String> labels,
        List<String> fixVersions,
        LocalDate dueDate,
        String externalKey,
        Long estimatedSeconds,
        Long spentSeconds,
        Instant issueCreated,
        Instant issueUpdated) {
}
