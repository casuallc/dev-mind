package com.devmind.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * agent_nodes 表：远程执行节点（CAP-21）。token 只存 SHA-256 哈希，明文仅创建时返回一次。
 */
@Entity
@Table(name = "agent_nodes")
public class AgentNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** ONLINE / OFFLINE / DISABLED */
    @Column(length = 16)
    private String status;

    /** 节点操作系统（runner hello 上报，如 "Windows 11 / amd64"） */
    @Column(length = 128)
    private String os;

    /** 标签（逗号分隔，调度预留） */
    @Column(length = 512)
    private String labels;

    /** 可用 agent 种类（逗号分隔，如 "claude"） */
    @Column(length = 256)
    private String capabilities;

    @Column(name = "runner_version", length = 64)
    private String runnerVersion;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** 平台默认执行节点（全平台至多一个）：会话与项目均未指定节点时回落到此（FR-03） */
    @Column(name = "is_default", nullable = false)
    @ColumnDefault("false")
    private boolean isDefault;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getRunnerVersion() { return runnerVersion; }
    public void setRunnerVersion(String runnerVersion) { this.runnerVersion = runnerVersion; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
