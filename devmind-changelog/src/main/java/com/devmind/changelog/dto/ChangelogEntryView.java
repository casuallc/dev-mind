package com.devmind.changelog.dto;

import com.devmind.changelog.model.ChangelogType;

import java.time.LocalDate;
import java.util.List;

/** 详情视图：列表摘要 + 完整 Markdown 正文。 */
public record ChangelogEntryView(
        String version,
        LocalDate releasedAt,
        List<ChangelogType> types,
        List<String> refs,
        String summary,
        String contentMd) {
}
