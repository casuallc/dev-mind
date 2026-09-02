package com.devmind.project.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Jira 同步专用字段包（托管字段）：同步通道 createFromJira/syncFromJira 使用，
 * 避免 integration 模块的 JiraIssue 类型渗入 project。
 * 托管字段 = title/description/type/priority/assignee/reporter/labels/fixVersions/dueDate/externalKey，
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
        String externalKey) {
}
