package com.devmind.project.dto;

import java.time.Instant;

/**
 * 工作单元视图（CAP-13）：code = WI-<seq> 项目内编号。
 */
public record WorkItemView(
        String id,
        String projectId,
        String requirementId,
        String designId,
        Long seq,
        String code,
        String type,
        String title,
        String spec,
        String status,
        String ownerId,
        String branchSlug,
        /** CAP-100 FR-02：归属会话 id（可空，最新一次回填） */
        String sessionId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 兼容 15 参旧构造（无 sessionId）。 */
    public WorkItemView(String id, String projectId, String requirementId, String designId,
                        Long seq, String code, String type, String title, String spec, String status,
                        String ownerId, String branchSlug, String createdBy,
                        Instant createdAt, Instant updatedAt) {
        this(id, projectId, requirementId, designId, seq, code, type, title, spec, status,
                ownerId, branchSlug, null, createdBy, createdAt, updatedAt);
    }
}
