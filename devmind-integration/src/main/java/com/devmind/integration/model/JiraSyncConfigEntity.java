package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * jira_sync_configs 表（CAP-19）：Jira → 平台的 issue 同步配置（单向只拉取）。
 * 一条配置 = 一个 JIRA 型 Integration + 内部项目 + Jira 项目 key + 附加 JQL；
 * 增量水印（last_watermark = 已处理的最大 updated，回拨 overlap）推进同步进度。
 * 同项目对同一 Jira 实例仅一条配置（唯一约束）。
 */
@Entity
@Table(name = "jira_sync_configs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"integration_id", "project_id"}))
public class JiraSyncConfigEntity {

    /** 单轮同步分页上限（防爆量） */
    public static final int MAX_PAGES_PER_RUN = 20;
    /** 每页条数 */
    public static final int PAGE_SIZE = 100;
    /** 水印回拨（防时钟/事务边界漏单；重复由 external_links 幂等兜住） */
    public static final long WATERMARK_OVERLAP_SECONDS = 60;
    /** 首轮同步窗口默认天数：只拉近 N 天有更新的 issue，防老项目全量灌入 */
    public static final int DEFAULT_FIRST_SYNC_DAYS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** integrations.id（JIRA 型） */
    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** Jira 项目 key（如 PROJ） */
    @Column(name = "jira_project_key", nullable = false, length = 64)
    private String jiraProjectKey;

    /** 附加 JQL 过滤片段（不含 project/updated/order by，如 "issuetype in (Story,Bug) AND labels = ai"） */
    @Column(name = "jql", length = 1024)
    private String jql;

    /** 首轮同步窗口（天）：0 = 不限（全量，慎用）；仅在无水印的首轮生效 */
    @Column(name = "first_sync_days", nullable = false)
    private int firstSyncDays = DEFAULT_FIRST_SYNC_DAYS;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 轮询间隔（秒），默认 5 分钟 */
    @Column(name = "poll_interval_sec", nullable = false)
    private int pollIntervalSec = 300;

    /** 上次实际执行时刻（错峰判断依据） */
    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** 增量水印：已处理的最大 issue updated（已回拨 overlap） */
    @Column(name = "last_watermark")
    private Instant lastWatermark;

    @Column(name = "last_imported")
    private Integer lastImported;

    @Column(name = "last_updated_count")
    private Integer lastUpdatedCount;

    /** 上次失败摘要（成功时清空） */
    @Column(name = "last_error", length = 1900)
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getJiraProjectKey() { return jiraProjectKey; }
    public void setJiraProjectKey(String jiraProjectKey) { this.jiraProjectKey = jiraProjectKey; }
    public String getJql() { return jql; }
    public void setJql(String jql) { this.jql = jql; }
    public int getFirstSyncDays() { return firstSyncDays; }
    public void setFirstSyncDays(int firstSyncDays) { this.firstSyncDays = firstSyncDays; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPollIntervalSec() { return pollIntervalSec; }
    public void setPollIntervalSec(int pollIntervalSec) { this.pollIntervalSec = pollIntervalSec; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public Instant getLastWatermark() { return lastWatermark; }
    public void setLastWatermark(Instant lastWatermark) { this.lastWatermark = lastWatermark; }
    public Integer getLastImported() { return lastImported; }
    public void setLastImported(Integer lastImported) { this.lastImported = lastImported; }
    public Integer getLastUpdatedCount() { return lastUpdatedCount; }
    public void setLastUpdatedCount(Integer lastUpdatedCount) { this.lastUpdatedCount = lastUpdatedCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
