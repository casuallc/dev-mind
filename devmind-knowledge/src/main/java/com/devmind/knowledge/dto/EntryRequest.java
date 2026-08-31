package com.devmind.knowledge.dto;

import java.util.List;

/**
 * 知识条目创建/更新请求。
 *
 * @param scope         global | project
 * @param projectId     project 范围必填
 * @param name          名称
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目
 * @param status        active | deprecated
 */
public record EntryRequest(
        String scope,
        String projectId,
        String name,
        String contentMd,
        List<String> tags,
        String sourceProject,
        String status) {
}
