package com.devmind.knowledge.dto;

import com.devmind.common.knowledge.KnowledgeRetriever;
import java.util.List;

/**
 * CAP-44 FR-06 检索请求/响应（POST /api/knowledge/search，前端检索测试页用）。
 *
 * @param vector         本次是否走的向量检索（false = LIKE 降级，UI 明示）
 * @param degradedReason CAP-48 FR-06 降级原因：NONE | NO_EMBEDDING | DIMENSION_MISMATCH
 */
public record KnowledgeSearchResponse(boolean vector,
                                      List<KnowledgeRetriever.RetrievedChunk> chunks,
                                      KnowledgeRetriever.DegradedReason degradedReason) {

    /** 检索请求。kbIds 目标库集合；topK 空/<=0 取配置默认。 */
    public record KnowledgeSearchRequest(List<Long> kbIds, String query, Integer topK) {
    }
}
