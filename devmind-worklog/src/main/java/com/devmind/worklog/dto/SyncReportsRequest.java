package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;

/** CAP-41：手动同步会话成稿请求（POST /api/worklog/reports/sync）。 */
public record SyncReportsRequest(@NotBlank String sessionId) {
}
