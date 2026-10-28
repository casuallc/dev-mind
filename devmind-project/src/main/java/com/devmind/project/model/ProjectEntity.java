package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * projects 表（CAP-02 FR-01）：本地 git 仓库项目注册。
 * tags 以逗号分隔存储（FR-02），contextSummary 为生成后可人工修正的项目上下文（FR-07）。
 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    /** CAP-41：项目种类——NORMAL（缺省，代码项目）/ WORKLOG（工作日志空间：runner 持久工作区，无仓库语义） */
    public static final String KIND_NORMAL = "NORMAL";
    public static final String KIND_WORKLOG = "WORKLOG";

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
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "description", length = 16_777_216)
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

    /** CAP-72：WI 翻 DONE 后自动触发构建（执行链第一环；null/false = 关，默认关） */
    @Column(name = "auto_build_on_wi_done")
    private Boolean autoBuildOnWiDone;

    /** CAP-72：构建成功后自动部署 TEST 环境（执行链第二环；null/false = 关，默认关） */
    @Column(name = "auto_deploy_test_on_build")
    private Boolean autoDeployTestOnBuild;

    /** CAP-100 FR-03：开发会话成功后自动把 IN_PROGRESS 工作单元置 DONE（null/false = 关，默认关） */
    @Column(name = "auto_done_wi_on_dev")
    private Boolean autoDoneWiOnDev;

    /** CAP-73：需求开发中验证总开关（null/false = 关，默认关） */
    @Column(name = "verify_in_dev_enabled")
    private Boolean verifyInDevEnabled;

    /** CAP-73：验证日配额（受理即计；null = 默认 20，0 = 不限） */
    @Column(name = "verify_daily_quota")
    private Integer verifyDailyQuota;

    /** CAP-21：默认执行节点（agent_nodes.id；null = 本机），创建会话未显式指定节点时继承 */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    /** 项目上下文摘要（FR-07，可人工修正） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "context_summary", length = 16_777_216)
    private String contextSummary;

    @Column(name = "summary_generated_at")
    private Instant summaryGeneratedAt;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /** CAP-41：项目种类（NORMAL/WORKLOG）；存量行 null 由 getter 兜底 NORMAL */
    @Column(length = 16)
    private String kind;

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
    /** CAP-72：Boolean 列不加 @ColumnDefault（MySQL bit 事故先例），存量行 null 兜底关 */
    public boolean isAutoBuildOnWiDone() { return Boolean.TRUE.equals(autoBuildOnWiDone); }
    public void setAutoBuildOnWiDone(Boolean autoBuildOnWiDone) { this.autoBuildOnWiDone = autoBuildOnWiDone; }
    public boolean isAutoDeployTestOnBuild() { return Boolean.TRUE.equals(autoDeployTestOnBuild); }
    public void setAutoDeployTestOnBuild(Boolean autoDeployTestOnBuild) { this.autoDeployTestOnBuild = autoDeployTestOnBuild; }
    /** CAP-100：Boolean 列不加 @ColumnDefault（同 CAP-72 红线），存量行 null 兜底关 */
    public boolean isAutoDoneWiOnDev() { return Boolean.TRUE.equals(autoDoneWiOnDev); }
    public void setAutoDoneWiOnDev(Boolean autoDoneWiOnDev) { this.autoDoneWiOnDev = autoDoneWiOnDev; }
    /** CAP-73：Boolean 列不加 @ColumnDefault（同 CAP-72 红线），存量行 null 兜底关 */
    public boolean isVerifyInDevEnabled() { return Boolean.TRUE.equals(verifyInDevEnabled); }
    public void setVerifyInDevEnabled(Boolean verifyInDevEnabled) { this.verifyInDevEnabled = verifyInDevEnabled; }
    /** CAP-73：null = 默认 20；0 = 不限 */
    public int getVerifyDailyQuota() { return verifyDailyQuota == null ? 20 : verifyDailyQuota; }
    public void setVerifyDailyQuota(Integer verifyDailyQuota) { this.verifyDailyQuota = verifyDailyQuota; }
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
    /** 旧行可能为 null（ddl-auto 历史数据），统一兜底 NORMAL */
    public String getKind() { return kind == null ? KIND_NORMAL : kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
