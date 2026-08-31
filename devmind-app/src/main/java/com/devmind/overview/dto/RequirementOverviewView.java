package com.devmind.overview.dto;

import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemView;

import java.time.Instant;
import java.util.List;

/**
 * 需求主线视图（CAP-13）：按 (projectId, requirementId) 聚合需求全过程对象
 * （工作单元/文档/会话/构建/测试/部署/发版/产物）。各条目为轻量摘要（不含日志/内容大字段）；
 * timeline 为跨类型时间线（倒序）。
 */
public record RequirementOverviewView(
        RequirementView requirement,
        List<WorkItemView> workItems,
        List<DocItem> docs,
        List<SessionItem> sessions,
        List<BuildItem> builds,
        List<TestRunItem> testRuns,
        List<DeploymentItem> deployments,
        List<ReleaseItem> releases,
        List<ArtifactItem> artifacts,
        List<TimelineItem> timeline) {

    public record DocItem(Long id, String kind, String title, String status, int currentVersion, Instant updatedAt) {
    }

    public record SessionItem(String id, String status, String taskSpec, String model, String workItemId,
                              Instant createdAt, Instant finishedAt) {
    }

    public record BuildItem(Long id, String status, String branch, String commit, String artifactRef,
                            String workItemId, Instant createdAt, Instant finishedAt) {
    }

    public record TestRunItem(Long id, String status, String summaryJson, Long reportDocId,
                              String triggeredBy, String workItemId, Instant createdAt, Instant finishedAt) {
    }

    public record DeploymentItem(Long id, String status, String env, Long serverId, Long buildId,
                                 String workItemId, String createdBy, Instant createdAt, Instant finishedAt) {
    }

    /** CAP-11：需求发版记录 */
    public record ReleaseItem(Long id, String version, String status, String executor,
                              Long rollbackOf, Instant createdAt, Instant finishedAt) {
    }

    /** CAP-13：工作产物条目 */
    public record ArtifactItem(Long id, String type, String name, String path, String producerType,
                               Instant createdAt) {
    }

    /** 时间线条目：type = REQUIREMENT / WORK_ITEM / DOC / SESSION / BUILD / TEST_RUN / DEPLOYMENT / RELEASE / ARTIFACT */
    public record TimelineItem(Instant time, String type, String label, String refId) {
    }
}
