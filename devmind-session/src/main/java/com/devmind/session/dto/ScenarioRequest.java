package com.devmind.session.dto;

import java.util.List;

/**
 * CAP-33 场景新建/更新请求。skillIds/docIds/knowledgeBaseIds 保存时不校验存在性
 * （资产可能后被删除，装配时对失效 id 严格 404，fail-visible）；知识库 id 落库为 JSON。
 */
public record ScenarioRequest(
        String code, String name, String description, String promptSkeleton,
        List<String> skillIds, List<Long> docIds, List<Long> knowledgeBaseIds,
        String extraContextMd, String model, String permissionMode, String agentNodeId,
        String scope, String projectId, Boolean enabled, Integer sortOrder,
        Integer autoFinishSec) {
}
