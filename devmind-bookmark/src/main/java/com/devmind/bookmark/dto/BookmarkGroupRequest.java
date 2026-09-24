package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotBlank;

/** FR-02 分组创建/编辑入参（parentId 可空 = 根分组；改 parentId 即移动父级）。 */
public record BookmarkGroupRequest(
        @NotBlank(message = "分组名必填") String name,
        Long parentId,
        Integer sortOrder) {
}
