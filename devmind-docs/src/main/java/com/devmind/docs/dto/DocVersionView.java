package com.devmind.docs.dto;

import java.time.Instant;

/**
 * 版本列表项（FR-02）：含变更说明与 commit_sha，不含正文。
 */
public record DocVersionView(
        Long documentId,
        int versionNo,
        String changeNote,
        String commitSha,
        String createdBy,
        Instant createdAt) {
}
