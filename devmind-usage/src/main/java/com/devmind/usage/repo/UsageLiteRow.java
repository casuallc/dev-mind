package com.devmind.usage.repo;

import java.time.Instant;

/**
 * 每日趋势的轻量投影行（避免 JPQL 日期截断的 H2/PG/MySQL 方言差异，Java 侧按 createdAt 日桶聚合）。
 * 字段全部包装类型——用量累计列对历史行可空。
 */
public record UsageLiteRow(Instant createdAt, Double costUsd, Long inputTokens, Long outputTokens,
                           Integer turnCount) {
}
