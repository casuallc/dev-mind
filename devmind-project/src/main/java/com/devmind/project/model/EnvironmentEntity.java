package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * environments 表（P1-1 Environment 模型）：项目内的部署/测试目标环境。
 * name 约定 DEV/TEST/STAGING/PROD（每项目同名唯一）；
 * serverIds 引用 servers 表（JSON 数组）；variables 为环境变量（JSON map）；
 * secrets 只存密钥名称列表（值永远不落库，由 CAP-07 凭证体系保管）。
 */
@Entity
@Table(name = "environments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class EnvironmentEntity {

    public static final String DEV = "DEV";
    public static final String TEST = "TEST";
    public static final String STAGING = "STAGING";
    public static final String PROD = "PROD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** DEV / TEST / STAGING / PROD */
    @Column(nullable = false, length = 32)
    private String name;

    @Column(length = 256)
    private String description;

    /** JSON 数组：servers 表 id 列表 */
    @Lob
    @Column(name = "server_ids_json")
    private String serverIdsJson;

    /** JSON map：环境变量（部署/测试时注入） */
    @Lob
    @Column(name = "variables_json")
    private String variablesJson;

    /** JSON 数组：密钥名称列表（仅名称引用，不存值） */
    @Lob
    @Column(name = "secrets_json")
    private String secretsJson;

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
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getServerIdsJson() { return serverIdsJson; }
    public void setServerIdsJson(String serverIdsJson) { this.serverIdsJson = serverIdsJson; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getSecretsJson() { return secretsJson; }
    public void setSecretsJson(String secretsJson) { this.secretsJson = secretsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
