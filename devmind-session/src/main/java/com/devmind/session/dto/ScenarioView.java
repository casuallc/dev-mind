package com.devmind.session.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-33 FR-01 场景视图（session_templates 的升级形态）。
 *
 * @param promptSkeleton prompt 骨架（占位符 {{task}}/{{project}}/{{branch}}/{{requirement}}）
 * @param skillIds       ①层绑定 skill ids
 * @param docIds         ①层绑定 doc ids
 * @param knowledgeTags  ①层绑定知识 tags
 * @param extraContextMd 场景背景（业务背景/口径约定 → CLAUDE.md「场景背景」节）
 * @param scope          GLOBAL | PROJECT
 */
public record ScenarioView(
        Long id, String code, String name, String description, String promptSkeleton,
        List<String> skillIds, List<Long> docIds, List<String> knowledgeTags,
        String extraContextMd, String model, String permissionMode, String agentNodeId,
        String scope, String projectId, boolean enabled, int sortOrder,
        Instant createdAt, Instant updatedAt) {
}
