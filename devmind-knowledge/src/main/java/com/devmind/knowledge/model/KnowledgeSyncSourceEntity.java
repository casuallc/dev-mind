package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-85 FR-27：外部知识同步源（Git 本地副本 / 飞书空间或目录）。
 * config_json 只存非敏感配置（路径/ref/集成 ID/目标/默认分类/标签），凭证留在各自集成模块。
 */
@Entity
@Table(name = "knowledge_sync_sources", indexes = {
        @Index(name = "idx_knowledge_sync_kb", columnList = "kb_id")
})
public class KnowledgeSyncSourceEntity {

    public static final String KIND_GIT = "GIT";
    public static final String KIND_FEISHU = "FEISHU";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long kbId;

    @Column(length = 16)
    private String kind;

    @Column(length = 200)
    private String name;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String configJson;

    private boolean enabled = true;

    /** Git commit SHA / 飞书最大 updatedAt（epoch millis） */
    @Column(length = 512)
    private String watermark;

    private Instant lastSyncAt;

    @Column(length = 16)
    private String lastStatus;

    @Column(length = 1000)
    private String lastError;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String lastStatsJson;

    @Column(length = 64)
    private String createdBy;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWatermark() { return watermark; }
    public void setWatermark(String watermark) { this.watermark = watermark; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getLastStatsJson() { return lastStatsJson; }
    public void setLastStatsJson(String lastStatsJson) { this.lastStatsJson = lastStatsJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
