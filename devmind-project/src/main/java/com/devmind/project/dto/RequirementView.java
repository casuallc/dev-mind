package com.devmind.project.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 需求视图（CAP-13 研发主线）：code = REQ-&lt;seq&gt; 项目内编号。
 * source=JIRA 时 externalKey 为 Jira issue key；externalUrl/remoteStatus 由外部引用查找端口
 * （RequirementExternalRefLookup）补充，integration 模块缺席时为 null。
 */
public record RequirementView(
        String id,
        String projectId,
        Long seq,
        String code,
        String title,
        String description,
        String status,
        String type,
        String ownerId,
        Long docId,
        String source,
        String priority,
        String assignee,
        String reporter,
        List<String> labels,
        List<String> fixVersions,
        LocalDate dueDate,
        String externalKey,
        String externalUrl,
        String remoteStatus,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
