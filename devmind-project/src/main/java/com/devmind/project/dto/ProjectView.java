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
        /** CAP-72：WI 翻 DONE 后自动触发构建 */
        boolean autoBuildOnWiDone,
        /** CAP-72：构建成功后自动部署 TEST 环境 */
        boolean autoDeployTestOnBuild,
        /** CAP-21：默认执行节点 id（null = 本机） */
        String agentNodeId,
        String contextSummary,
        Instant summaryGeneratedAt,
        String ownerId,
        /** CAP-41：项目种类（NORMAL/WORKLOG） */
        String kind,
        /** CAP-73：需求开发中验证总开关 */
        boolean verifyInDevEnabled,
        /** CAP-73：验证日配额（0=不限） */
        int verifyDailyQuota,
        Instant createdAt,
        Instant updatedAt) {
}
