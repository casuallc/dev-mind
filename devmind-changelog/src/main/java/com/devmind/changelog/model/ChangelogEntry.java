package com.devmind.changelog.model;

import java.time.LocalDate;
import java.util.List;

/**
 * CAP-87 解析后的单版本条目（内部模型）。内容正文保留原始 Markdown；
 * 关联编号行与提交锚点行已从正文剥离，单独存放。
 *
 * @param commit 打包时 HEAD 的短哈希（M4「当前部署」对照锚点），旧条目可空
 */
public record ChangelogEntry(
        String version,
        LocalDate releasedAt,
        String commit,
        List<ChangelogType> types,
        List<String> refs,
        String summary,
        String contentMd) {
}
