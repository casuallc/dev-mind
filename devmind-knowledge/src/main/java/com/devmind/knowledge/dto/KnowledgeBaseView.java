package com.devmind.knowledge.dto;

import java.time.Instant;

/**
 * 知识库视图（CAP-44 FR-01，CAP-48 FR-06 增补端点与索引健康度）。
 *
 * @param id                库 ID
 * @param name              名称
 * @param description       描述
 * @param scope             global | project
 * @param projectId         scope=project 时所属项目
 * @param projectName       scope=project 时项目名（展示用，解析失败为空）
 * @param injectMode        FULL=全量注入 CLAUDE.md | RAG=仅检索
 * @param modelEndpointId   库级覆盖的 embedding 端点 ID（null = 平台默认端点）
 * @param modelEndpointName 实际生效的端点名（含回落平台默认后的结果；无可用端点时 null）
 * @param status            active | archived
 * @param entryCount        条目数
 * @param chunkCount        分块数
 * @param indexStats        索引健康度（CAP-48 FR-06）
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record KnowledgeBaseView(
        Long id,
        String name,
        String description,
        String scope,
        String projectId,
        String projectName,
        String injectMode,
        Long modelEndpointId,
        String modelEndpointName,
        String status,
        long entryCount,
        long chunkCount,
        IndexStats indexStats,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 索引健康度：条目的索引状态分布 + 失配数。
     *
     * @param mismatched 已 ready 但血缘与本库当前生效端点不一致的条目数
     *                   （换端点/换模型/维度变了）——大于 0 表示该库检索会静默劣化，
     *                   UI 应提示「重建索引」；无法判定时（无可用端点/端点未探测过维度）为 0
     */
    public record IndexStats(long ready, long pending, long failed, long disabled, long mismatched) {
    }
}
