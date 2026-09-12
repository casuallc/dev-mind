package com.devmind.session.dto;

import java.time.Instant;

/** CAP-39：会话产出文件列表项（GET /sessions/{id}/outputs）。 */
public record OutputFileView(String fileName, long sizeBytes, Instant updatedAt) {
}
