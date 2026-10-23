package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-84 FR-13 全局检索响应：BM25 + 向量 RRF 融合后按文档聚合，附高亮片段与原文定位。
 *
 * @param vector         本次是否实际走了向量通道
 * @param degradedReason NONE | NO_EMBEDDING | DIMENSION_MISMATCH | INDEX_EMPTY
 */
public record KnowledgeSearchV2Response(
        List<Hit> hits,
        int total,
        boolean vector,
        String degradedReason) {

    /**
     * 文档级命中。{@code snippet}/{@code chunkId}/heading/offsets 为最高分块（兼容旧前端）；
     * {@code snippets} 为最多 3 个高亮片段，{@code chunks} 为最多 5 个命中块明细。
     */
    public record Hit(
            Long entryId,
            Long kbId,
            String kbName,
            String title,
            String docType,
            String path,
            String sourceUri,
            String summary,
            List<String> tags,
            String snippet,
            long chunkId,
            String headingPath,
            int startOffset,
            int endOffset,
            double score,
            int chunkCount,
            Instant updatedAt,
            List<Snippet> snippets,
            List<ChunkRef> chunks) {
    }

    /** 高亮片段：text 已转义并带 {@code <mark>} 标记，前端按文本节点渲染。 */
    public record Snippet(String text, long chunkId, String headingPath,
                          int startOffset, int endOffset) {
    }

    /** 命中分块明细（「展开证据」用）。 */
    public record ChunkRef(long chunkId, int chunkIndex, String headingPath,
                           int startOffset, int endOffset, String snippet, double score) {
    }
}
