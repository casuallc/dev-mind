package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目创建/更新请求。CAP-44 起 kbId 优先；为兼容旧调用，scope/projectId 仍可传
 * （服务端据此解析/兜底建经验库）。CAP-84 起可指定文档类型与治理字段。
 *
 * @param kbId          所属知识库（优先）
 * @param scope         global | project（旧调用兼容）
 * @param projectId     project 范围必填（旧调用兼容）
 * @param name          名称
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目
 * @param status        active | deprecated
 * @param docType       TECH | MAINT | FAQ | ARTIFACT（缺省 TECH）
 * @param sourceUri     来源地址/相对路径
 * @param summary       摘要（缺省取正文前 300 字）
 * @param owner         负责人
 * @param validFrom     生效时间
 * @param validTo       失效时间（过期后检索与注入自动排除）
 */
public record EntryRequest(
        Long kbId,
        String scope,
        String projectId,
        String name,
        String contentMd,
        List<String> tags,
        String sourceProject,
        String status,
        String docType,
        String sourceUri,
        String summary,
        String owner,
        Instant validFrom,
        Instant validTo) {
}
