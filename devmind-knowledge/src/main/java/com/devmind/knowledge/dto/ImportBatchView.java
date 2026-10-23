package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/** CAP-84 FR-07 导入批次视图（含逐文件状态）。 */
public record ImportBatchView(
        String id,
        Long kbId,
        String taskId,
        String name,
        String defaultDocType,
        String tags,
        String status,
        int totalFiles,
        int successCount,
        int failedCount,
        int skippedCount,
        String error,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<File> files) {

    public record File(
            Long id,
            String relativePath,
            String fileName,
            String mimeType,
            long sizeBytes,
            String status,
            String error,
            Long entryId) {
    }
}
