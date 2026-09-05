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
 * worklog_repo_subscriptions 表（CAP-28 FR-02）：用户勾选参与的全局仓库子集。
 * git 扫描只覆盖勾选仓库。
 */
@Entity
@Table(name = "worklog_repo_subscriptions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "repo_id"}))
public class WorklogRepoSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username，弱关联不设外键） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
