package com.devmind.common.authz;

import java.time.Instant;

/**
 * CAP-108 §5 授权决策审计事件（authorization_decisions 表的数据形状）。
 * 不记录 token、secret 或完整敏感正文。
 */
public record AuthorizationAuditEvent(
        Instant checkedAt,
        String traceId,
        String commandId,
        String subjectType,
        String subjectId,
        String actor,
        String roleCode,
        String clientId,
        String credentialId,
        String source,
        String operationId,
        Integer operationVersion,
        String resourceType,
        String resourceId,
        String projectId,
        /** ALLOW / DENY / ERROR */
        String decision,
        String reasonCode,
        Integer policyVersion,
        String actionId,
        String operationKey) {
}
