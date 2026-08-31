package com.devmind.overview.dto;

import com.devmind.project.dto.RequirementView;

import java.time.Instant;
import java.util.List;

/**
 * 需求主线视图（P0-6 落地步骤 4）：按 (projectId, requirementId) 聚合需求全过程对象。
 * 各条目为轻量摘要（不含日志/内容大字段）；timeline 为跨类型时间线（倒序）。
 */
public record RequirementOverviewView(
        RequirementView requirement,
        List<DocItem> docs,
        List<SessionItem> sessions,
        List<BuildItem> builds,
        List<TestRunItem> testRuns,
        List<DeploymentItem> deployments,
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

    /** 时间线条目：type = REQUIREMENT / DOC / SESSION / BUILD / TEST_RUN / DEPLOYMENT */
    public record TimelineItem(Instant time, String type, String label, String refId) {
    }
}
