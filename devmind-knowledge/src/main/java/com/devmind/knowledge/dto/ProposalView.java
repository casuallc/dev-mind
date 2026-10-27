package com.devmind.knowledge.dto;

import java.time.Instant;

/**
 * 经验提案视图（FR-05/FR-06）。CAP-94 起去向改为采纳时显式选库（adoptedKbId），
 * 项目维度只剩 sourceProject 溯源标注。
 *
 * @param id               提案 ID
 * @param title            标题
 * @param contentMd        内容
 * @param sourceSessionId  来源会话
 * @param sourceProject    来源项目（溯源标注）
 * @param status           open | adopted | rejected
 * @param adoptedKbId      采纳到的知识库（未采纳为 null）
 * @param adoptedKbName    采纳到的知识库名（展示用）
 * @param createdAt        创建时间
 * @param adoptedAt        采纳/丢弃时间
 * @param triage           CAP-55 FR-04 AI 分诊建议（null = 从未分诊，前端不渲染徽标区块）
 */
public record ProposalView(
        Long id,
        String title,
        String contentMd,
        String sourceSessionId,
        String sourceProject,
        String status,
        Long adoptedKbId,
        String adoptedKbName,
        Instant createdAt,
        Instant adoptedAt,
        TriageView triage) {
}
