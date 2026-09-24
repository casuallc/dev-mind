package com.devmind.bookmark.dto;

/** FR-03 标签引用（挂在收藏上的最小形态：id + 名称）。 */
public record BookmarkTagView(Long id, String name) {
}
