package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * workspace_cleanup_config 表（CAP-114）：会话工作区定时清理策略——平台级单行配置（id 固定 1）。
 * 无配置行 = 默认关闭（页面 GET 返回默认值不落库，PUT 才建行）。
 *
 * <p>Boolean/Integer 列一律可空 + Service 层默认值兜底（Boolean 列禁 @ColumnDefault，
 * 存量表平滑加列，同 worklog_user_settings 惯例）。</p>
 */
@Entity
@Table(name = "workspace_cleanup_config")
public class WorkspaceCleanupConfigEntity {

    /** 单行配置固定主键 */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** 总开关（null/false = 关闭，默认关） */
    @Column(name = "enabled")
    private Boolean enabled;

    /** 保留天数：终态会话工作区超过该天数未活动即清理（1~365，默认 30） */
    @Column(name = "retention_days")
    private Integer retentionDays;

    /** 每日执行时间（小时，0~23，默认 3） */
    @Column(name = "run_hour")
    private Integer runHour;

    /** 是否清需求级工作区（req- 键，默认 true） */
    @Column(name = "scope_requirement")
    private Boolean scopeRequirement;

    /** 是否清会话级工作区（sid- 键与 CAP-42 旧布局，默认 true） */
    @Column(name = "scope_session")
    private Boolean scopeSession;

    /** 上次执行时间（防同日重跑 + 页面展示） */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** 上次执行报告（JSON 文本，页面展示） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "last_report", length = 16_777_216)
    private String lastReport;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Integer getRunHour() {
        return runHour;
    }

    public void setRunHour(Integer runHour) {
        this.runHour = runHour;
    }

    public Boolean getScopeRequirement() {
        return scopeRequirement;
    }

    public void setScopeRequirement(Boolean scopeRequirement) {
        this.scopeRequirement = scopeRequirement;
    }

    public Boolean getScopeSession() {
        return scopeSession;
    }

    public void setScopeSession(Boolean scopeSession) {
        this.scopeSession = scopeSession;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastReport() {
        return lastReport;
    }

    public void setLastReport(String lastReport) {
        this.lastReport = lastReport;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
