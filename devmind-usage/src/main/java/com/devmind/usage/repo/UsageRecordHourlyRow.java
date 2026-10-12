package com.devmind.usage.repo;

import java.time.Instant;

/** 按小时分桶的轻量投影行（hourly 桶在 Java 侧做，避开 JPQL hour() 的三库方言差异）。 */
public record UsageRecordHourlyRow(Instant createdAt, Long inputTokens, Long outputTokens) {
}
