package com.devmind.bookmark.dto;

import java.time.Instant;
import java.util.List;

/**
 * FR-01 收藏出参。last* 为 FR-04 探测结果与 FR-06 最近访问；accounts 为 FR-05（密码已脱敏）。
 */
public record BookmarkView(
        String id,
        Long groupId,
        String title,
        String url,
        String description,
        String faviconUrl,
        int sortOrder,
        String lastStatus,
        String lastStatusCode,
        Long lastLatencyMs,
        Instant lastCheckedAt,
        Instant lastVisitedAt,
        Instant createdAt,
        Instant updatedAt,
        List<BookmarkTagView> tags,
        List<BookmarkAccountView> accounts) {
}
