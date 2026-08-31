package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * servers 表（CAP-02 FR-03）：项目下关联服务器（引用 CAP-07 实体之前的落点）。
 * accessConfig 为连接配置（SSH/HTTP），MVP 存明文 JSON，标记 accessType；后续接入 CAP-07 加密。
 */
@Entity
@Table(name = "servers")
public class ProjectServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    /** test / staging / prod */
    @Column(length = 16)
    private String env;

    /** ssh / http */
    @Column(name = "access_type", length = 16)
    private String accessType;

    /** JSON 连接配置（主机/用户/端口/密钥路径 或 base-url/token 引用） */
    @Lob
    @Column(name = "access_config")
    private String accessConfig;

    /** 逗号分隔的能力列表（build/deploy/test/release） */
    @Column(length = 256)
    private String capabilities;

    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
    public String getAccessConfig() { return accessConfig; }
    public void setAccessConfig(String accessConfig) { this.accessConfig = accessConfig; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
