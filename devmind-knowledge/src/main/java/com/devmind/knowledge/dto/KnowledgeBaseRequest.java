package com.devmind.knowledge.dto;

/**
 * 知识库创建/更新请求（CAP-44 FR-01）。CAP-94 起知识库去项目化：不再有 scope/projectId
 * （旧客户端传了由 Jackson 忽略，不报错）。
 *
 * @param name            名称（创建必填）
 * @param description     描述
 * @param modelEndpointId CAP-48 FR-04 库级覆盖的 embedding 端点（空 = 平台默认端点）
 * @param status          active | archived
 */
public record KnowledgeBaseRequest(
        String name,
        String description,
        Long modelEndpointId,
        String status) {
}
