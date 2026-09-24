package com.devmind.bookmark.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FR-05 账号出参。密码恒脱敏：{@code passwordMasked} 为固定掩码而非明文，
 * 明文只能经 {@code GET /api/bookmarks/{id}/accounts/{aid}/secret} 按次取。
 *
 * <p>{@code passwordMasked} 为 null 时序列化直接剔除该字段——分享视图（FR-07「密码恒不分享」）
 * 与「本就没有密码」两种情况都借这个口径表达。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookmarkAccountView(
        String id,
        String label,
        String username,
        String passwordMasked,
        boolean hasPassword,
        String note,
        int sortOrder) {
}
