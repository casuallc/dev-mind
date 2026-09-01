package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * integration_calls 表（CAP-18 FR-08）：出站调用审计，不含任何凭据明文。
 * 与全局 audit_log 互补：这里按 integration/action 维度可查询、可排障。
 */
@Entity
@Table(name = "integration_calls")
public class IntegrationCallEntity {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id")
    private Long integrationId;

    /** test / push_branch / create_mr / push_tag / create_release / … */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "internal_type", length = 24)
    private String internalType;

    @Column(name = "internal_id", length = 64)
    private String internalId;

    /** SUCCESS / FAILED */
    @Column(nullable = false, length = 16)
    private String result;

    /** 失败原因（已脱敏，token 替换为 ***） */
    @Column(length = 2000)
    private String error;

    @Column(length = 64)
    private String actor;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getInternalType() { return internalType; }
    public void setInternalType(String internalType) { this.internalType = internalType; }
    public String getInternalId() { return internalId; }
    public void setInternalId(String internalId) { this.internalId = internalId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
