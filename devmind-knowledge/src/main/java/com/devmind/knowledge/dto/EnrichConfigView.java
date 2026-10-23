package com.devmind.knowledge.dto;

/** CAP-85 FR-28：自动增强配置摘要（治理页展示开关与批量口径，不含端点凭据）。 */
public record EnrichConfigView(boolean enabled, Long endpointId, int maxInputChars,
                               int batchLimit, int timeoutSeconds) {
}
