package com.devmind.knowledge.dto;

import java.util.List;

/**
 * CAP-84 FR-13 全局检索请求（POST /api/knowledge/search/v2）。
 * kbIds 为空 = 全部启用中的知识库；docTypes/tags/projectId 为可选过滤，tags 多选为 OR。
 */
public record KnowledgeSearchV2Request(
        List<Long> kbIds,
        String query,
        List<String> docTypes,
        String projectId,
        List<String> tags,
        Integer topK) {
}
