package com.devmind.build.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * build_configs 表（CAP-08 FR-02）：每项目一份构建配置——执行位置 local/remote、
 * 远程目标服务器、同项目并发构建数上限。有序步骤列表沿用 CAP-02 build_steps。
 */
@Entity
@Table(name = "build_configs")
public class BuildConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32, unique = true)
    private String projectId;

    /** LOCAL / AGENT（CAP-36：REMOTE/SSH 已下线，AGENT = runner 节点执行） */
    @Column(nullable = false, length = 16)
    private String executor = "LOCAL";

    /** AGENT 执行时的目标节点 id（CAP-36；空 = 触发时走路由链） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    @Column(name = "concurrency_limit")
    private int concurrencyLimit = 1;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public int getConcurrencyLimit() { return concurrencyLimit; }
    public void setConcurrencyLimit(int concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
