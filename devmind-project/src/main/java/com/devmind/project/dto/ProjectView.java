package com.devmind.project.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-23：sourceType/cloneStatus 为主库镜像（null = 存量纯本地项目）。
 */
public record ProjectView(
        String id,
        String name,
        String path,
        String defaultBranch,
        List<String> tags,
        String description,
        String status,
        String sourceType,
        String cloneStatus,
        String apiDocSource,
        Boolean autoRegressionOnDeploy,
        /** CAP-21：默认执行节点 id（null = 本机） */
        String agentNodeId,
        String contextSummary,
        Instant summaryGeneratedAt,
        String ownerId,
        Instant createdAt,
        Instant updatedAt) {
}
