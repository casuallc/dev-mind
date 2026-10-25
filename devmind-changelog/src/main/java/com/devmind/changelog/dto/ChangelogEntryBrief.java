package com.devmind.changelog.dto;

import com.devmind.changelog.model.ChangelogType;

import java.time.LocalDate;
import java.util.List;

/** 列表项摘要：不含完整正文，保证首屏与分页响应体积可控。 */
public record ChangelogEntryBrief(
        String version,
        LocalDate releasedAt,
        List<ChangelogType> types,
        List<String> refs,
        String summary) {
}
