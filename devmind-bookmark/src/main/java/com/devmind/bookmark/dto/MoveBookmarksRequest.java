package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * FR-02 批量转移分组入参。
 *
 * @param ids     收藏 id 列表
 * @param groupId 目标分组；null = 未分组
 */
public record MoveBookmarksRequest(
        @NotEmpty(message = "至少选择一条收藏") List<Long> ids,
        Long groupId) {
}
