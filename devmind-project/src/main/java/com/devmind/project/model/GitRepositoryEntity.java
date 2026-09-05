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
 * git_repositories 表（CAP-29）：全局代码仓库登记，平台级共享、不挂项目。
 * 管理写操作仅 ADMIN；项目仓库（project_repos.git_repo_id）只关联本表，不复制数据。
 *
 * <p>local_path：CLONE 行 = 服务端克隆目录（{@code <workspace-root>/_global/<slug>-<sha8>}，
 * 登记前确定性计算，满足首插非空）；LOCAL 行 = 平台所在机已存在的检出路径。</p>
 */
@Entity
@Table(name = "git_repositories")
public class GitRepositoryEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_CLONE = "CLONE";

    public static final String CLONE_NONE = "NONE";
    public static final String CLONE_CLONING = "CLONING";
    public static final String CLONE_READY = "READY";
    public static final String CLONE_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 服务端本地绝对路径，唯一 */
    @Column(name = "local_path", nullable = false, length = 1024, unique = true)
    private String localPath;

    /** 远端地址（可空）：host 用于 CAP-24 按用户解析署名邮箱 */
    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    /** 规范化 remoteUrl（小写 host、去 .git/尾斜杠、git@ 归一），upsert 匹配键 */
    @Column(name = "remote_url_key", length = 512, unique = true)
    private String remoteUrlKey;

    /** 默认分支（fetch 时按远端 HEAD 刷新） */
    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    /** LOCAL=登记已存在路径；CLONE=服务端克隆 */
    @Column(name = "source_type", nullable = false, length = 16)
    @ColumnDefault("'LOCAL'")
    private String sourceType = SOURCE_LOCAL;

    /** 克隆/抓取所用平台集成（integrations.id 弱引用；null=匿名） */
    @Column(name = "integration_id")
    private Long integrationId;

    @Column(name = "clone_status", nullable = false, length = 16)
    @ColumnDefault("'NONE'")
    private String cloneStatus = CLONE_NONE;

    @Column(name = "clone_error", length = 1024)
    private String cloneError;

    /** 远程分支列表（换行分隔，fetch 刷新） */
    @Lob
    @Column(length = 16_777_216)
    private String branches;

    @Column(name = "last_fetch_at")
    private Instant lastFetchAt;

    @Column(name = "last_fetch_error", length = 1024)
    private String lastFetchError;

    @Column(nullable = false, length = 16)
    @ColumnDefault("'ACTIVE'")
    private String status = STATUS_ACTIVE;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }
    public String getRemoteUrlKey() { return remoteUrlKey; }
    public void setRemoteUrlKey(String remoteUrlKey) { this.remoteUrlKey = remoteUrlKey; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getCloneStatus() { return cloneStatus; }
    public void setCloneStatus(String cloneStatus) { this.cloneStatus = cloneStatus; }
    public String getCloneError() { return cloneError; }
    public void setCloneError(String cloneError) { this.cloneError = cloneError; }
    public String getBranches() { return branches; }
    public void setBranches(String branches) { this.branches = branches; }
    public Instant getLastFetchAt() { return lastFetchAt; }
    public void setLastFetchAt(Instant lastFetchAt) { this.lastFetchAt = lastFetchAt; }
    public String getLastFetchError() { return lastFetchError; }
    public void setLastFetchError(String lastFetchError) { this.lastFetchError = lastFetchError; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
