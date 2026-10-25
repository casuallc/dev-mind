package com.devmind.changelog.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分页响应（page 从 0 起）。在通用分页字段外附带数据源状态，
 * 文件缺失时前端据此展示可执行提示而不是空表。
 */
public record ChangelogPageView(
        List<ChangelogEntryBrief> items,
        long total,
        int page,
        int size,
        boolean available,
        String sourcePath,
        Instant loadedAt) {
}
