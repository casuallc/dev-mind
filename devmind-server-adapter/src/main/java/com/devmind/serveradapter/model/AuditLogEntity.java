package com.devmind.serveradapter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * audit_logs 表（CAP-07 FR-06 执行审计）：所有经适配器的连接/执行/传输/健康检查全量留痕。
 * 只记命令与输出摘要，绝不记录凭证配置（FR-07）。
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 32)
    private String projectId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "server_name", length = 128)
    private String serverName;

    /** ssh / http */
    @Column(name = "access_type", length = 16)
    private String accessType;

    /** connect_test / execute / upload / download / health_check */
    @Column(nullable = false, length = 24)
    private String action;

    /** 模板 code（execute/logs 时） */
    @Column(length = 64)
    private String templateCode;

    /** 能力（build/deploy/test/…） */
    @Column(length = 24)
    private String capability;

    /** 渲染后的命令/脚本（模板内容，不含凭证） */
    @Lob
    @Column(name = "command")
    private String command;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "success")
    private Boolean success;

    /** 输出摘要（stdout/stderr 尾段，截断） */
    @Lob
    @Column(name = "detail")
    private String detail;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
