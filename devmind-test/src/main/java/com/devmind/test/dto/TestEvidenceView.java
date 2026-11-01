package com.devmind.test.dto;

import java.time.Instant;

public record TestEvidenceView(
        Long id,
        Long runId,
        Long resultId,
        String nodeId,
        String kind,
        String filename,
        String sha256,
        String prevHash,
        Long sizeBytes,
        Integer seqNo,
        Instant createdAt) {
}
