package com.devmind.bookmark.dto;

/** FR-03 标签列表项（带引用计数，供前端标签管理面板展示与删除确认）。 */
public record BookmarkTagSummary(Long id, String name, long bookmarkCount) {
}
