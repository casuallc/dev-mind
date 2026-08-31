package com.devmind.project.dto;

import java.time.Instant;

/**
 * 通用关系边视图（CAP-13）。
 */
public record RelationView(
        String id,
        String projectId,
        String fromType,
        String fromId,
        String toType,
        String toId,
        String relationType,
        Instant createdAt) {
}
