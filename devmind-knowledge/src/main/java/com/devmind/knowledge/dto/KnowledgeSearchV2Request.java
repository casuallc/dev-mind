package com.devmind.knowledge.dto;

import java.util.List;

/**
 * CAP-84 FR-13 全局检索请求（POST /api/knowledge/search/v2）。
 * kbIds 为空 = 全部启用中的知识库；docTypes/tags 为可选过滤，tags 多选为 OR。
 * CAP-94 起无 projectId 维度（知识库去项目化，检索范围一律显式 kbIds）。
 */
public record KnowledgeSearchV2Request(
        List<Long> kbIds,
        String query,
        List<String> docTypes,
        List<String> tags,
        Integer topK) {
}
