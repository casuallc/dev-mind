package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * release_config 表（CAP-02 FR-05）：Nexus 推送脚本模板引用 + 目标仓库 + 版本规则（委托 CAP-11）。
 */
@Entity
@Table(name = "release_config")
public class ReleaseConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32, unique = true)
    private String projectId;

    /** Nexus 目标仓库（snapshots/releases/...） */
    @Column(name = "nexus_repo", length = 256)
    private String nexusRepo;

    /** 推送脚本模板引用（如 docs-repo 中模板 id 或路径） */
    @Column(name = "script_template_ref", length = 512)
    private String scriptTemplateRef;

    /** 版本规则描述（如 semver 递增策略） */
    @Column(name = "version_rule", length = 512)
    private String versionRule;

    /** CAP-11 执行方式：LOCAL（本机渲染模板正文执行）/ AGENT（CAP-36：渲染后经 exec 帧下发 runner 节点）；默认 LOCAL */
    @Column(name = "executor", length = 16)
    private String executor = "LOCAL";

    /** CAP-36 节点执行目标（executor=AGENT 时生效；空走节点路由链） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getNexusRepo() { return nexusRepo; }
    public void setNexusRepo(String nexusRepo) { this.nexusRepo = nexusRepo; }
    public String getScriptTemplateRef() { return scriptTemplateRef; }
    public void setScriptTemplateRef(String scriptTemplateRef) { this.scriptTemplateRef = scriptTemplateRef; }
    public String getVersionRule() { return versionRule; }
    public void setVersionRule(String versionRule) { this.versionRule = versionRule; }
    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
