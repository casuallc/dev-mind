package com.devmind.knowledge.dto;

/**
 * CAP-84 FR-15 文档详情「分块」页签：只暴露定位与正文，不回传历史 embedding 列。
 */
public record KnowledgeChunkView(
        long id,
        int chunkIndex,
        String headingPath,
        int startOffset,
        int endOffset,
        int tokenCount,
        String content) {
}
