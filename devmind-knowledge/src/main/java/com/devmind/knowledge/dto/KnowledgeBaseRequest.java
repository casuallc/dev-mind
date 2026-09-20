package com.devmind.knowledge.dto;

/**
 * 知识库创建/更新请求（CAP-44 FR-01）。
 *
 * @param name            名称（创建必填）
 * @param description     描述
 * @param scope           global | project（创建必填，缺省 global）
 * @param projectId       scope=project 必填
 * @param injectMode      FULL | RAG（缺省 RAG）
 * @param modelEndpointId CAP-48 FR-04 库级覆盖的 embedding 端点（空 = 平台默认端点）
 * @param status          active | archived
 */
public record KnowledgeBaseRequest(
        String name,
        String description,
        String scope,
        String projectId,
        String injectMode,
        Long modelEndpointId,
        String status) {
}
