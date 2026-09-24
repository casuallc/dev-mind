package com.devmind.bookmark.dto;

import java.time.Instant;

/**
 * FR-07 分享边出参（「我发出的分享」列表用，带解析后的目标名便于前端展示与撤销）。
 */
public record BookmarkShareView(
        Long id,
        Long bookmarkId,
        String bookmarkTitle,
        Long groupId,
        String groupName,
        String targetUser,
        Instant createdAt) {
}
