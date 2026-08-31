package com.devmind.artifact.dto;

import java.time.Instant;

public record ArtifactView(
        Long id,
        String projectId,
        String taskId,
        String type,
        String name,
        String version,
        String checksum,
        String storage,
        String path,
        String producerType,
        Long producerId,
        String createdBy,
        Instant createdAt) {
}
