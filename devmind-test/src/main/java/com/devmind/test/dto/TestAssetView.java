package com.devmind.test.dto;

import com.devmind.common.program.ProgramManifest;

import java.time.Instant;

/** CAP-104 测试资产视图（GIT 源 / ZIP / 单文件 / M1.5 程序包）。 */
public record TestAssetView(
        Long id,
        String projectId,
        String kind,
        String name,
        String source,
        String branch,
        String commitHash,
        String sha256,
        Long sizeBytes,
        Integer fileCount,
        String reviewStatus,
        String uploadedBy,
        String reviewedBy,
        Instant reviewedAt,
        String note,
        Instant createdAt,
        // CAP-104 M1.5
        String assetRole,
        String runtime,
        String entrypoint,
        String targetOs,
        String targetArch,
        ProgramManifest manifest) {
}
