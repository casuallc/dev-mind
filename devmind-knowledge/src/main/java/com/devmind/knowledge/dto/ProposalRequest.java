package com.devmind.knowledge.dto;

/**
 * 经验提案创建请求（FR-05）。CAP-94 起不再有「去向」：提案只记录内容与溯源，
 * 采纳时由审核人显式选择目标库（adopt?targetKbId=）。
 *
 * @param title           标题
 * @param contentMd       内容
 * @param sourceSessionId 来源会话 ID
 * @param sourceProject   来源项目（纯溯源标注，可空）
 */
public record ProposalRequest(
        String title,
        String contentMd,
        String sourceSessionId,
        String sourceProject) {
}
