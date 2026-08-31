package com.devmind.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * audit_logs 表——全局审计（P0-3 自 server-adapter 提升）：各域操作全量留痕。
 * server-adapter 的连接/执行/传输/健康检查是 domain=server 的一类；
 * 其他域（build/deploy/task/…）经 {@link AuditService} 写入。
 * 只记操作与结果摘要，绝不记录凭证。
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 域：server / build / deploy / test / task / … */
    @Column(length = 24)
    private String domain;

    /** 操作者（P0-2 Identity；系统触发填 system） */
    @Column(length = 64)
    private String actor;

    @Column(name = "project_id", length = 32)
    private String projectId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "server_name", length = 128)
    private String serverName;

    /** ssh / http */
    @Column(name = "access_type", length = 16)
    private String accessType;

    /** connect_test / execute / upload / download / health_check / trigger / confirm / … */
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
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
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
