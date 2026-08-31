package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目视图。
 *
 * @param id            条目 ID
 * @param scope         global | project
 * @param projectId     项目范围所属项目（global 为 null）
 * @param name          名称
 * @param path          逻辑路径
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目
 * @param hitCount      被注入次数
 * @param status        active | deprecated
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record EntryView(
        Long id,
        String scope,
        String projectId,
        String name,
        String path,
        String contentMd,
        List<String> tags,
        String sourceProject,
        int hitCount,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
