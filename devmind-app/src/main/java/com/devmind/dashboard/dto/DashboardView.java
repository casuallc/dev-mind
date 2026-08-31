package com.devmind.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 指挥中心聚合视图（CAP-16）：全局只读聚合，无新增表。
 */
public record DashboardView(
        /** 需求状态分布：status -> 数量（七个状态齐全，缺省 0） */
        Map<String, Long> requirements,
        /** 活跃会话（RUNNING/WAITING_INPUT/WAITING_AUTH，WAITING_* 在等人） */
        List<ActiveSessionItem> activeSessions,
        /** 待人工验收的需求（ACCEPTANCE） */
        List<PendingRequirementItem> pendingAcceptance,
        /** 待确认的方案（DRAFT） */
        List<PendingDesignItem> pendingDesigns,
        /** 最近失败（构建/部署/测试/发版 FAILED，时间倒序，最多 10 条） */
        List<FailureItem> recentFailures) {

    public record ActiveSessionItem(String id, String projectId, String requirementId, String workItemId,
                                    String taskSpec, String status, Instant createdAt) {
    }

    public record PendingRequirementItem(String id, String projectId, String code, String title) {
    }

    public record PendingDesignItem(String id, String projectId, String requirementId,
                                    Integer version, Long docId) {
    }

    public record FailureItem(String type, String id, String projectId, String label, Instant time) {
    }
}
