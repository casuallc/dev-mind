package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.Map;

/**
 * CAP-85 FR-27：同步源视图。config 只含非敏感配置；lastStats 为最近一次运行统计。
 */
public record KnowledgeSyncSourceView(
        Long id,
        Long kbId,
        String kind,
        String name,
        Map<String, Object> config,
        boolean enabled,
        String watermark,
        Instant lastSyncAt,
        String lastStatus,
        String lastError,
        SyncStats lastStats,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public record SyncStats(int created, int updated, int unchanged, int deprecated, int failed) {
        public static SyncStats empty() {
            return new SyncStats(0, 0, 0, 0, 0);
        }
    }
}
