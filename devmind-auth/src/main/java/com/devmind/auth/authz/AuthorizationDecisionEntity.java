package com.devmind.auth.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CAP-108 §5 authorization_decisions：全入口最终 ALLOW/DENY/ERROR 审计。
 * 不记录 token、secret 或完整敏感正文。
 */
@Entity
@Table(name = "authorization_decisions")
public class AuthorizationDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "command_id", length = 64)
    private String commandId;

    @Column(name = "subject_type", length = 16)
    private String subjectType;

    @Column(name = "subject_id", length = 64)
    private String subjectId;

    @Column(length = 64)
    private String actor;

    @Column(name = "role_code", length = 32)
    private String roleCode;

    @Column(name = "client_id", length = 64)
    private String clientId;

    @Column(name = "credential_id", length = 64)
    private String credentialId;

    @Column(length = 16)
    private String source;

    @Column(name = "operation_id", length = 128)
    private String operationId;

    @Column(name = "operation_version")
    private Integer operationVersion;

    @Column(name = "resource_type", length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "project_id", length = 32)
    private String projectId;

    /** ALLOW / DENY / ERROR */
    @Column(length = 8)
    private String decision;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "action_id", length = 64)
    private String actionId;

    @Column(name = "operation_key", length = 128)
    private String operationKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public Integer getOperationVersion() { return operationVersion; }
    public void setOperationVersion(Integer operationVersion) { this.operationVersion = operationVersion; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Integer getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Integer policyVersion) { this.policyVersion = policyVersion; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }
}
