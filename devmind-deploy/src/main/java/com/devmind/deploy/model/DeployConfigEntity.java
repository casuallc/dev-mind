package com.devmind.deploy.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * deploy_configs 表（CAP-09 FR-01）：每项目一份部署计划定义。
 * steps_json 为部署步骤（拉产物→备份→部署→启动→健康检查），rollback_steps_json 为回滚步骤；
 * 两者均为 [{name,type,templateCode,params}] JSON 数组，templateCode 走 CAP-07 模板白名单。
 */
@Entity
@Table(name = "deploy_configs")
public class DeployConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true, length = 32)
    private String projectId;

    @Lob
    @Column(name = "steps_json")
    private String stepsJson;

    @Lob
    @Column(name = "rollback_steps_json")
    private String rollbackStepsJson;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }
    public String getRollbackStepsJson() { return rollbackStepsJson; }
    public void setRollbackStepsJson(String rollbackStepsJson) { this.rollbackStepsJson = rollbackStepsJson; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
