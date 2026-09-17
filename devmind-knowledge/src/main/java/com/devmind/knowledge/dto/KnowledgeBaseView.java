package com.devmind.knowledge.dto;

import java.time.Instant;

/**
 * 知识库视图（CAP-44 FR-01）。
 *
 * @param id              库 ID
 * @param name            名称
 * @param description     描述
 * @param scope           global | project
 * @param projectId       scope=project 时所属项目
 * @param projectName     scope=project 时项目名（展示用，解析失败为空）
 * @param injectMode      FULL=全量注入 CLAUDE.md | RAG=仅检索
 * @param embeddingModel  覆盖平台默认 embedding 模型（空=平台默认）
 * @param status          active | archived
 * @param entryCount      条目数
 * @param chunkCount      分块数
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 */
public record KnowledgeBaseView(
        Long id,
        String name,
        String description,
        String scope,
        String projectId,
        String projectName,
        String injectMode,
        String embeddingModel,
        String status,
        long entryCount,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt) {
}
