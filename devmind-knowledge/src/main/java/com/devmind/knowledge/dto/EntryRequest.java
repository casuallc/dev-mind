package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目创建/更新请求。CAP-94 起 kbId 必填（知识库为唯一归属，不再按 scope/projectId
 * 兜底找/建经验库）。CAP-84 起可指定文档类型与治理字段。
 *
 * @param kbId          所属知识库（必填）
 * @param name          名称
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目（纯溯源标注）
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
