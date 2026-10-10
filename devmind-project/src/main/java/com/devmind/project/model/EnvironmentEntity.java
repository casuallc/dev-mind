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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * environments 表（P1-1 Environment 模型）：项目内的部署/测试目标环境。
 * name 约定 DEV/TEST/STAGING/PROD（每项目同名唯一）；
 * nodeIds 引用 agent_nodes 表 id（JSON 数组，CAP-36：部署/测试目标 = runner 节点）；
 * variables 为环境变量（JSON map）；secrets 只存密钥名称列表（值永远不落库）。
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

    /** JSON 数组：agent_nodes 表 id 列表（字符串；CAP-36 起部署/测试目标 = runner 节点） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "node_ids_json", length = 16_777_216)
    private String nodeIdsJson;

    /** JSON map：环境变量（部署/测试时注入） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "variables_json", length = 16_777_216)
    private String variablesJson;

    /** JSON 数组：密钥名称列表（仅名称引用，不存值） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "secrets_json", length = 16_777_216)
    private String secretsJson;

    /** CAP-73：补丁验证部署目录（补丁覆盖目标；空 = 该环境不可用于验证） */
    @Column(name = "patch_dir", length = 512)
    private String patchDir;

    /** CAP-73：覆盖文件前命令（停服/备份；空 = 直接覆盖） */
    @Column(name = "patch_pre_hook", length = 1024)
    private String patchPreHook;

    /** CAP-73：覆盖文件后命令（启服/热加载；空 = 不动进程） */
    @Column(name = "patch_post_hook", length = 1024)
    private String patchPostHook;

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
    public String getNodeIdsJson() { return nodeIdsJson; }
    public void setNodeIdsJson(String nodeIdsJson) { this.nodeIdsJson = nodeIdsJson; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getSecretsJson() { return secretsJson; }
    public void setSecretsJson(String secretsJson) { this.secretsJson = secretsJson; }
    public String getPatchDir() { return patchDir; }
    public void setPatchDir(String patchDir) { this.patchDir = patchDir; }
    public String getPatchPreHook() { return patchPreHook; }
    public void setPatchPreHook(String patchPreHook) { this.patchPreHook = patchPreHook; }
    public String getPatchPostHook() { return patchPostHook; }
    public void setPatchPostHook(String patchPostHook) { this.patchPostHook = patchPostHook; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
