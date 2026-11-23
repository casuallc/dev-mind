package com.devmind.session.dto;

import java.time.Instant;

/**
 * CAP-114：工作区定时清理策略视图（GET/PUT 共用）。
 * 布尔字段一律 Boolean 包装（Jackson 3 红线：null → primitive 直接抛错）。
 */
public record WorkspaceCleanupConfigView(
        Boolean enabled,
        Integer retentionDays,
        Integer runHour,
        Boolean scopeRequirement,
        Boolean scopeSession,
        Instant lastRunAt,
        String lastReport) {
}
