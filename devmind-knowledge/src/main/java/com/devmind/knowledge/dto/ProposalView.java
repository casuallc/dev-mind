package com.devmind.knowledge.dto;

import java.time.Instant;

/**
 * 经验提案视图（FR-05/FR-06）。
 *
 * @param id               提案 ID
 * @param title            标题
 * @param contentMd        内容
 * @param targetScope      期望去向：project | global
 * @param targetProjectId  项目去向的目标项目
 * @param sourceSessionId  来源会话
 * @param status           open | adopted | rejected
 * @param adoptedTo        实际采纳去向
 * @param adoptedProjectId 采纳到的项目
 * @param createdAt        创建时间
 * @param adoptedAt        采纳/丢弃时间
 */
public record ProposalView(
        Long id,
        String title,
        String contentMd,
        String targetScope,
        String targetProjectId,
        String sourceSessionId,
        String status,
        String adoptedTo,
        String adoptedProjectId,
        Instant createdAt,
        Instant adoptedAt) {
}
