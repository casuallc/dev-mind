package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * FR-07 发起分享。bookmarkId / groupId 恰好一个非空（服务层校验）；targetUser 为平台内 username。
 */
public record BookmarkShareRequest(
        Long bookmarkId,
        Long groupId,
        @NotBlank(message = "请选择分享对象") String targetUser) {
}
