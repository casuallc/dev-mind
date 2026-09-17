package com.devmind.knowledge.dto;

import java.util.List;

/**
 * 知识条目创建/更新请求。CAP-44 起 kbId 优先；为兼容旧调用，scope/projectId 仍可传
 * （服务端据此解析/兜底建经验库）。
 *
 * @param kbId          所属知识库（优先）
 * @param scope         global | project（旧调用兼容）
 * @param projectId     project 范围必填（旧调用兼容）
 * @param name          名称
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目
 * @param status        active | deprecated
 */
public record EntryRequest(
        Long kbId,
        String scope,
        String projectId,
        String name,
        String contentMd,
        List<String> tags,
        String sourceProject,
        String status) {
}
