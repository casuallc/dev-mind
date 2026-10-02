package com.devmind.bookmark.dto;

/**
 * FR-09 导入结果汇报：新建分组/收藏计数 + 重复跳过 + 非法地址跳过（前端 toast 原样汇报）。
 */
public record ImportResultView(int createdGroups,
                               int createdBookmarks,
                               int skippedDuplicates,
                               int skippedInvalid) {
}
