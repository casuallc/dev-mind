package com.devmind.security.dto;

import java.time.Instant;

/**
 * CVE 抑制请求（CAP-66 FR-10-3）：构件 × 漏洞 双轨抑制。
 *
 * @param artifactId 可用 * 表整组
 * @param projectId  可空 = 平台级全局生效（仅 ADMIN）
 * @param type       FALSE_POSITIVE / ACCEPTED_RISK（后者强制 expiresAt）
 */
public record CveSuppressionRequest(String groupId, String artifactId, String cve, String projectId,
                                    String type, String reason, Instant expiresAt) {
}
