package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-86 库内条目列表摘要：轻量投影，不含 contentMd/metadataJson 两个 LOB 列，
 * 供万级文档的文档页服务端分页使用。
 */
public record EntrySummaryView(
        Long id,
        Long kbId,
        String name,
        String docType,
        List<String> tags,
        String source,
        String externalId,
        String indexStatus,
        String indexError,
        String summary,
        String owner,
        int hitCount,
        String status,
        String sourceUri,
        int contentVersion,
        Instant updatedAt) {
}
