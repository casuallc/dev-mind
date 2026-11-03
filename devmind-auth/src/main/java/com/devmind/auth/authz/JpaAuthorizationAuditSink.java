package com.devmind.auth.authz;

import com.devmind.common.authz.AuthorizationAuditEvent;
import com.devmind.common.authz.AuthorizationAuditSink;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-108 FR-07 审计落库：独立事务（REQUIRES_NEW）——拒绝/放行记录不随业务事务回滚丢失。
 */
@Component
public class JpaAuthorizationAuditSink implements AuthorizationAuditSink {

    private final AuthorizationDecisionRepository repo;

    public JpaAuthorizationAuditSink(AuthorizationDecisionRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthorizationAuditEvent event) {
        AuthorizationDecisionEntity e = new AuthorizationDecisionEntity();
        e.setCheckedAt(event.checkedAt());
        e.setTraceId(event.traceId());
        e.setCommandId(event.commandId());
        e.setSubjectType(event.subjectType());
        e.setSubjectId(event.subjectId());
        e.setActor(event.actor());
        e.setRoleCode(event.roleCode());
        e.setClientId(event.clientId());
        e.setCredentialId(event.credentialId());
        e.setSource(event.source());
        e.setOperationId(event.operationId());
        e.setOperationVersion(event.operationVersion());
        e.setResourceType(event.resourceType());
        e.setResourceId(event.resourceId());
        e.setProjectId(event.projectId());
        e.setDecision(event.decision());
        e.setReasonCode(event.reasonCode());
        e.setPolicyVersion(event.policyVersion());
        e.setActionId(event.actionId());
        e.setOperationKey(event.operationKey());
        repo.save(e);
    }
}
