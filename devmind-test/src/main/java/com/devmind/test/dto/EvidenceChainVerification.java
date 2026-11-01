package com.devmind.test.dto;

/** CAP-104 证据链校验结果（导出清单与运维核验共用）。 */
public record EvidenceChainVerification(
        boolean valid,
        int count,
        String headHash,
        String message) {
}
