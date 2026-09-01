package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * integration_bindings 表（CAP-18 FR-03）：Integration 与项目仓库（CAP-02 project_repos）的绑定。
 * external_project_key 存平台侧项目标识（GitLab project id 或 URL-encoded path）；
 * 同项目同类型仅允许一个 ENABLED 绑定（服务层保证）。
 */
@Entity
@Table(name = "integration_bindings")
public class IntegrationBindingEntity {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** project_repos.id（默认主库） */
    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    /** 平台侧项目标识：GitLab project id / path_with_namespace（URL-encoded 亦可） */
    @Column(name = "external_project_key", nullable = false, length = 256)
    private String externalProjectKey;

    @Column(length = 16)
    private String status = STATUS_ENABLED;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getExternalProjectKey() { return externalProjectKey; }
    public void setExternalProjectKey(String externalProjectKey) { this.externalProjectKey = externalProjectKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
