package com.devmind.changelog.model;

import java.time.LocalDate;
import java.util.List;

/**
 * CAP-87 解析后的单版本条目（内部模型）。内容正文保留原始 Markdown；
 * 关联编号行已从正文剥离，单独存放。
 */
public record ChangelogEntry(
        String version,
        LocalDate releasedAt,
        List<ChangelogType> types,
        List<String> refs,
        String summary,
        String contentMd) {
}
