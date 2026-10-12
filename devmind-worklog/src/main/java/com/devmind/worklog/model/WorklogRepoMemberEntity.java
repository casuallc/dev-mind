package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * worklog_repo_members 表（CAP-74 FR-01）：仓成员——成员各写各的 subdir 子目录
 * （= username，路径不相交结构性消除跨人冲突）。UK(repo_id,user_id) 防重复加入，
 * UK(repo_id,subdir) 防子目录撞名。
 */
@Entity
@Table(name = "worklog_repo_members",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"repo_id", "user_id"}),
                @UniqueConstraint(columnNames = {"repo_id", "subdir"})
        })
public class WorklogRepoMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 仓内子目录名（= username；写入路径 &lt;subdir&gt;/daily|weekly/...） */
    @Column(name = "subdir", nullable = false, length = 64)
    private String subdir;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSubdir() { return subdir; }
    public void setSubdir(String subdir) { this.subdir = subdir; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
