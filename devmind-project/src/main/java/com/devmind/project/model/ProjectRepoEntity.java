package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * project_repos 表（P0-4 项目多库模型）：项目 = 多 git 库组合。
 * 每个仓库有角色（CODE/DOCS/CONFIG），且恰好一个主库（is_primary=1）；
 * projects.path / default_branch 作为主库的镜像列保留，供既有消费方（会话/构建/摘要扫描）无感使用。
 */
@Entity
@Table(name = "project_repos")
public class ProjectRepoEntity {

    /** 代码库（默认角色） */
    public static final String ROLE_CODE = "CODE";
    /** 文档库 */
    public static final String ROLE_DOCS = "DOCS";
    /** 配置库 */
    public static final String ROLE_CONFIG = "CONFIG";

    /** CAP-23 仓库来源：本地已有路径（默认，存量无感） */
    public static final String SOURCE_LOCAL = "LOCAL";
    /** CAP-23 仓库来源：从远端 git 克隆（GitLab/GitHub） */
    public static final String SOURCE_CLONE = "CLONE";

    /** CAP-23 克隆状态：本地库/未克隆 */
    public static final String CLONE_NONE = "NONE";
    /** CAP-23 克隆状态：克隆中 */
    public static final String CLONE_CLONING = "CLONING";
    /** CAP-23 克隆状态：就绪 */
    public static final String CLONE_READY = "READY";
    /** CAP-23 克隆状态：失败 */
    public static final String CLONE_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    /** 本地 git 仓库绝对路径 */
    @Column(nullable = false, length = 512)
    private String path;

    /** 远端地址（可选，仅记录） */
    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    /** CODE / DOCS / CONFIG */
    @Column(length = 16)
    private String role = ROLE_CODE;

    /** 是否主库（项目内唯一） */
    @Column(name = "is_primary")
    private Boolean isPrimary = false;

    @Column(name = "sort_order")
    private int sortOrder;

    /** CAP-23：LOCAL（本地路径）/ CLONE（远端克隆，path 由系统计算） */
    @Column(name = "source_type", length = 16)
    @ColumnDefault("'LOCAL'")
    private String sourceType = SOURCE_LOCAL;

    /** CAP-23：克隆认证所用 Integration 实例（弱关联，不设外键；null = 匿名克隆公开仓库） */
    @Column(name = "integration_id")
    private Long integrationId;

    /** CAP-23 克隆状态：NONE / CLONING / READY / FAILED */
    @Column(name = "clone_status", length = 16)
    @ColumnDefault("'NONE'")
    private String cloneStatus = CLONE_NONE;

    /** CAP-23 克隆失败摘要（已脱敏） */
    @Column(name = "clone_error", length = 1024)
    private String cloneError;

    /** CAP-23 全量克隆日志（WS 快照 + REST 回放用，对齐 BuildEntity.logsText 先例） */
    @Lob
    @Column(name = "clone_logs")
    private String cloneLogs;

    /** CAP-23 最近克隆成功时间 */
    @Column(name = "cloned_at")
    private Instant clonedAt;

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
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Boolean getIsPrimary() { return isPrimary; }
    public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getCloneStatus() { return cloneStatus; }
    public void setCloneStatus(String cloneStatus) { this.cloneStatus = cloneStatus; }
    public String getCloneError() { return cloneError; }
    public void setCloneError(String cloneError) { this.cloneError = cloneError; }
    public String getCloneLogs() { return cloneLogs; }
    public void setCloneLogs(String cloneLogs) { this.cloneLogs = cloneLogs; }
    public Instant getClonedAt() { return clonedAt; }
    public void setClonedAt(Instant clonedAt) { this.clonedAt = clonedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
