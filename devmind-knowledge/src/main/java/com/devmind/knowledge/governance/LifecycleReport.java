package com.devmind.knowledge.governance;

import java.time.Instant;
import java.util.List;

/**
 * CAP-85 FR-29 生命周期报告：四组互相独立（同一篇过期且缺负责人的文档会同时出现），
 * 各自带总数字段——列表按 limit 截断，总数仍能告诉治理页"还有多少没看"。
 */
public record LifecycleReport(Instant generatedAt, int expiringDays, int staleDays, int limit,
                              long expiredCount, long expiringCount, long staleCount, long missingOwnerCount,
                              List<Item> expired, List<Item> expiring, List<Item> stale,
                              List<Item> missingOwner) {

    public record Item(Long id, Long kbId, String name, String docType, String owner,
                       Instant validTo, Instant lastReviewedAt, Instant updatedAt) {
    }
}
