package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CAP-31 session_repos 表：会话创建时从 project_repos 拷值快照（仓库可能随后被改/移除，
 * 会话生命周期以快照为准——resume/清理/远程 diff 均读快照，不回查项目现值）。
 */
@Entity
@Table(name = "session_repos", indexes = @Index(name = "idx_session_repos_sid", columnList = "session_id"))
public class SessionRepoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 32)
    private String sessionId;

    /** 溯源 project_repos.id（可空：项目仓库行被删后快照仍自洽） */
    @Column(name = "project_repo_id")
    private Long projectRepoId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    /** 创建时本地仓库绝对路径（本地 worktree/服务端 diff 用） */
    @Column(name = "local_path", length = 512)
    private String localPath;

    @Column(name = "base_branch", length = 128)
    private String baseBranch;

    /** 会话分支（feature/&lt;sessionId&gt;，各库同名） */
    @Column(length = 128)
    private String branch;

    @Column(name = "is_primary")
    private Boolean isPrimary = false;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getProjectRepoId() { return projectRepoId; }
    public void setProjectRepoId(Long projectRepoId) { this.projectRepoId = projectRepoId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public Boolean getIsPrimary() { return isPrimary; }
    public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
