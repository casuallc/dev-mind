package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * project_lock 表（CAP-02 FR-09）：并发写控制基础——同项目最大并发写任务数，供 Orchestrator 使用。
 */
@Entity
@Table(name = "project_lock")
public class ProjectLockEntity {

    @Id
    @Column(length = 32)
    private String projectId;

    @Column(name = "active_writes")
    private int activeWrites;

    @Column(name = "max_concurrent")
    private int maxConcurrent;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getActiveWrites() { return activeWrites; }
    public void setActiveWrites(int activeWrites) { this.activeWrites = activeWrites; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
