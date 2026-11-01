package com.devmind.test.dto;

import java.util.List;
import java.util.Map;

/** CAP-104 M1.5 运行详情里的程序输出视图（原始 result.json 在 RESULT_JSON 证据中）。 */
public record ProgramRunView(
        Long assetId,
        String contractVersion,
        String name,
        String version,
        String runtime,
        String runtimeVersion,
        String entrypoint,
        String targetOs,
        String targetArch,
        String status,
        String message,
        Map<String, Object> metrics,
        List<Artifact> artifacts) {

    public record Artifact(String path, Long sizeBytes, String sha256) {
    }
}
