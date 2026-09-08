package com.devmind.session.dto;

import java.util.List;

/**
 * CAP-33 场景新建/更新请求。skillIds/docIds 保存时不校验存在性（资产可能后被删除，
 * 装配时对失效 id 严格 404，fail-visible）；knowledgeTags 列表形式，落库存 CSV。
 */
public record ScenarioRequest(
        String code, String name, String description, String promptSkeleton,
        List<String> skillIds, List<Long> docIds, List<String> knowledgeTags,
        String extraContextMd, String model, String permissionMode, String agentNodeId,
        String scope, String projectId, Boolean enabled, Integer sortOrder) {
}
