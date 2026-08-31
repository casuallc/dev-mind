package com.devmind.knowledge.dto;

/**
 * 经验提案创建请求（FR-05）。
 *
 * @param title           标题
 * @param contentMd       内容
 * @param targetScope     期望去向：project | global
 * @param targetProjectId 项目去向的目标项目（沉淀到项目时建议传）
 * @param sourceSessionId 来源会话 ID
 */
public record ProposalRequest(
        String title,
        String contentMd,
        String targetScope,
        String targetProjectId,
        String sourceSessionId) {
}
