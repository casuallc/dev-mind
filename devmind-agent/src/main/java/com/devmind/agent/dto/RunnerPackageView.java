package com.devmind.agent.dto;

import com.devmind.agent.model.RunnerPackageEntity;

import java.time.Instant;

/** runner 托管包元数据视图（CAP-21 FR-09）。 */
public record RunnerPackageView(Long id, String version, String sha256, Long sizeBytes,
                                String originalFilename, Instant uploadedAt, String uploadedBy) {

    public static RunnerPackageView from(RunnerPackageEntity e) {
        return new RunnerPackageView(e.getId(), e.getVersion(), e.getSha256(), e.getSizeBytes(),
                e.getOriginalFilename(), e.getUploadedAt(), e.getUploadedBy());
    }
}
