package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * security_suppressions 表（CAP-66 FR-05）：fingerprint 抑制（钉代码位置，适用 SAST/secrets）。
 * 过期自动失效——匹配判定时按 expires_at 过滤，不回写状态。
 */
@Entity
@Table(name = "security_suppressions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_security_supp", columnNames = {"project_id", "repo_id", "engine", "fingerprint"})
})
public class SecuritySuppressionEntity {

    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String ACCEPTED_RISK = "ACCEPTED_RISK";
    public static final String THIRD_PARTY = "THIRD_PARTY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;
    @Column(name = "repo_id", nullable = false)
    private Long repoId;
    @Column(nullable = false, length = 16)
    private String engine;
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(name = "created_by", length = 64)
    private String createdBy;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
