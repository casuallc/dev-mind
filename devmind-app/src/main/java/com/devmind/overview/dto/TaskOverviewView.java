package com.devmind.overview.dto;

import com.devmind.project.dto.TaskView;

import java.time.Instant;
import java.util.List;

/**
 * 任务主线视图（P0-6 落地步骤 4）：按 (projectId, taskId) 聚合任务全过程对象
 * （文档/会话/构建/测试/部署/发版）。各条目为轻量摘要（不含日志/内容大字段）；
 * timeline 为跨类型时间线（倒序）。
 */
public record TaskOverviewView(
        TaskView task,
        List<DocItem> docs,
        List<SessionItem> sessions,
        List<BuildItem> builds,
        List<TestRunItem> testRuns,
        List<DeploymentItem> deployments,
        List<ReleaseItem> releases,
        List<TimelineItem> timeline) {

    public record DocItem(Long id, String kind, String title, String status, int currentVersion, Instant updatedAt) {
    }

    public record SessionItem(String id, String status, String taskSpec, String model,
                              Instant createdAt, Instant finishedAt) {
    }

    public record BuildItem(Long id, String status, String branch, String commit, String artifactRef,
                            Instant createdAt, Instant finishedAt) {
    }

    public record TestRunItem(Long id, String status, String summaryJson, Long reportDocId,
                              String triggeredBy, Instant createdAt, Instant finishedAt) {
    }

    public record DeploymentItem(Long id, String status, String env, Long serverId, Long buildId,
                                 String createdBy, Instant createdAt, Instant finishedAt) {
    }

    /** CAP-11：任务发版记录 */
    public record ReleaseItem(Long id, String version, String status, String executor,
                              Long rollbackOf, Instant createdAt, Instant finishedAt) {
    }

    /** 时间线条目：type = TASK / DOC / SESSION / BUILD / TEST_RUN / DEPLOYMENT / RELEASE */
    public record TimelineItem(Instant time, String type, String label, String refId) {
    }
}
