package com.devmind.test.executor;

import com.devmind.common.program.ProgramManifest;

import java.util.List;
import java.util.Map;

/**
 * CAP-104 M1.5 程序运行结果（附录 C 契约 v1）：清单快照、实际输入、程序原始输出、
 * 结构化结果与产物内容。证据层据此写 PROGRAM_MANIFEST/INPUT_JSON/RESULT_JSON/ARTIFACT。
 */
public record ProgramOutcome(
        Long assetId,
        String assetSha256,
        String contractVersion,
        ProgramManifest manifest,
        String manifestJson,
        String inputJson,
        String resultJson,
        String resultStatus,
        String resultMessage,
        Map<String, Object> metrics,
        List<ProgramArtifact> artifacts) {

    public record ProgramArtifact(String path, byte[] content, String sha256) {
    }
}
