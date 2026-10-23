package com.devmind.knowledge.dto;

import java.time.Instant;

/** CAP-85 FR-31：治理页自动增强状态行（不含正文，万级条目下也可安全分页展示）。 */
public record EnrichEntryView(
        Long id,
        Long kbId,
        String name,
        String docType,
        boolean autoEnrich,
        Instant enrichedAt,
        String enrichError,
        Instant updatedAt) {
}
