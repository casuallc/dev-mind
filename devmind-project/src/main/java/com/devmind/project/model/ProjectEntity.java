package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * projects 表（CAP-02 FR-01）：本地 git 仓库项目注册。
 * tags 以逗号分隔存储（FR-02），contextSummary 为生成后可人工修正的项目上下文（FR-07）。
 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 本地 git 仓库绝对路径 */
    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    /** 逗号分隔的标签（java/backend/frontend/...） */
    @Column(length = 512)
    private String tags;

    @Lob
    @Column(name = "description")
    private String description;

    /** ACTIVE / ARCHIVED */
    @Column(length = 16)
    private String status;

    /** CAP-23：主库来源镜像（LOCAL / CLONE；null = 存量纯本地项目），随 syncPrimaryMirror 维护 */
    @Column(name = "source_type", length = 16)
    private String sourceType;

    /** CAP-23：主库克隆状态镜像（NONE / CLONING / READY / FAILED；null = 纯本地项目），供列表页徽标 */
    @Column(name = "clone_status", length = 16)
    private String cloneStatus;

    /** 指向 OpenAPI 文件（项目内路径或文档库），供测试套件生成（FR-06） */
    @Column(name = "api_doc_source", length = 512)
    private String apiDocSource;

    /** CAP-10 FR-05：部署成功后自动触发测试回归 */
    @Column(name = "auto_regression_on_deploy")
    private Boolean autoRegressionOnDeploy;

    /** CAP-21：默认执行节点（agent_nodes.id；null = 本机），创建会话未显式指定节点时继承 */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    /** 项目上下文摘要（FR-07，可人工修正） */
    @Lob
    @Column(name = "context_summary")
    private String contextSummary;

    @Column(name = "summary_generated_at")
    private Instant summaryGeneratedAt;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCloneStatus() { return cloneStatus; }
    public void setCloneStatus(String cloneStatus) { this.cloneStatus = cloneStatus; }
    public String getApiDocSource() { return apiDocSource; }
    public void setApiDocSource(String apiDocSource) { this.apiDocSource = apiDocSource; }
    public Boolean getAutoRegressionOnDeploy() { return autoRegressionOnDeploy; }
    public void setAutoRegressionOnDeploy(Boolean autoRegressionOnDeploy) { this.autoRegressionOnDeploy = autoRegressionOnDeploy; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String contextSummary) { this.contextSummary = contextSummary; }
    public Instant getSummaryGeneratedAt() { return summaryGeneratedAt; }
    public void setSummaryGeneratedAt(Instant summaryGeneratedAt) { this.summaryGeneratedAt = summaryGeneratedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
