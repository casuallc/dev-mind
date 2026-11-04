package com.devmind.security.dto;

import java.time.Instant;

/**
 * 建抑制请求（CAP-66 FR-05）：findingId 带出项目/仓库/引擎/fingerprint 四元组。
 *
 * @param type      FALSE_POSITIVE / ACCEPTED_RISK / THIRD_PARTY
 * @param reason    理由（必填，审计追溯）
 * @param expiresAt 过期时间（可空 = 永久；过期即失效，同 fingerprint 下次扫描重新 NEW）
 */
public record SuppressionRequest(Long findingId, String type, String reason, Instant expiresAt) {
}
