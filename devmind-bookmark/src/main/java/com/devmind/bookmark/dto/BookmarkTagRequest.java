package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotBlank;

/** FR-03 标签创建/改名入参。 */
public record BookmarkTagRequest(@NotBlank(message = "标签名必填") String name) {
}
