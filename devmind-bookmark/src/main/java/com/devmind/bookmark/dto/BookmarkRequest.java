package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * FR-01 收藏创建/全量编辑入参（PUT 为全量语义：tags/accounts 整组替换）。
 */
public record BookmarkRequest(
        @NotBlank(message = "标题必填") String title,
        @NotBlank(message = "URL 必填") String url,
        String description,
        Long groupId,
        List<Long> tagIds,
        List<BookmarkAccountRequest> accounts,
        String faviconUrl,
        Integer sortOrder) {
}
