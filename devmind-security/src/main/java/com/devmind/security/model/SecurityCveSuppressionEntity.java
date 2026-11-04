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
 * security_cve_suppressions 表（CAP-66 FR-10-3）：CVE 抑制（钉「构件 × 漏洞」，
 * 适用 SCA 跨项目复用）。project_id null = 平台级全局生效（仅 ADMIN 可建删）；
 * ACCEPTED_RISK 强制 expires_at。
 *
 * <p>注意：uk 含可空列 project_id——H2/MySQL 的唯一约束把 NULL 视为互不等值，
 * 平台级条目重复创建不会撞 uk，应用层 save 前必须先查重。</p>
 */
@Entity
@Table(name = "security_cve_suppressions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_security_cve_supp",
                columnNames = {"group_id", "artifact_id", "cve", "project_id"})
})
public class SecurityCveSuppressionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, length = 256)
    private String groupId;

    /** '*' = 该 group 整组 */
    @Column(name = "artifact_id", nullable = false, length = 256)
    private String artifactId;

    @Column(nullable = false, length = 32)
    private String cve;

    /** null = 平台级全局生效 */
    @Column(name = "project_id", length = 64)
    private String projectId;

    /** FALSE_POSITIVE / ACCEPTED_RISK（ACCEPTED_RISK 强制 expires_at） */
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
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getCve() { return cve; }
    public void setCve(String cve) { this.cve = cve; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
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
