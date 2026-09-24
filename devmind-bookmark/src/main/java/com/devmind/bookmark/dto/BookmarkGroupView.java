package com.devmind.bookmark.dto;

import java.util.List;

/**
 * FR-02 分组树节点。
 *
 * @param bookmarkCount 本组直接挂载的收藏数（不含子分组）
 * @param children      子分组（服务端已组装成树）
 */
public record BookmarkGroupView(
        Long id,
        Long parentId,
        String name,
        int sortOrder,
        long bookmarkCount,
        List<BookmarkGroupView> children) {
}
