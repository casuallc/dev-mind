package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * git_repositories 表（CAP-28 FR-01）：全局代码仓库登记，平台级共享、不挂项目。
 * 登记写操作仅 ADMIN；local_path 为平台所在机的本地绝对路径。
 */
@Entity
@Table(name = "git_repositories")
public class GitRepositoryEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 本地绝对路径（平台所在机），唯一 */
    @Column(name = "local_path", nullable = false, length = 1024, unique = true)
    private String localPath;

    /** 远端地址（可空）：host 用于 CAP-24 按用户解析署名邮箱 */
    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    /** 扫描分支；空 = 当前 HEAD */
    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

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
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
