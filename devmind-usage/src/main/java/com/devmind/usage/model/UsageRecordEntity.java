package com.devmind.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 逐回合用量账本：一行 = 一个回合（claude result 帧 / 模型执行体一轮生成）。
 *
 * <p>与 sessions/chat_sessions 的六列累计互补：累计列供汇总/分组/每日趋势（口径不变），
 * 本表供请求日志与按小时趋势（精确回合时刻归属）。token 列全部可空——执行体不上报
 * 该项就是 NULL（如 CAP-49 模型执行体只有 durationMs），不 coerce 0，与「空=未上报」
 * 语义保持一致；{@code costUsd} 照常住账但前端不展示（订阅/中继场景成本失真）。</p>
 *
 * <p>{@code owner}/{@code model} 是写时从会话实体快照的归属列：统计侧 owner 强制与
 * 模型筛选靠它们，不 join 会话表（会话删除后账本仍在）。标题/requirementId/projectId
 * 不冗余——taskSpec 是 16MB 级 LOB，查询时按页内 id 批量 join 并服务端截断。</p>
 */
@Entity
@Table(name = "usage_records", indexes = {
        @Index(name = "idx_usage_records_owner_time", columnList = "owner, created_at"),
        @Index(name = "idx_usage_records_time", columnList = "created_at"),
        @Index(name = "idx_usage_records_session", columnList = "session_id")})
public class UsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源：SESSION / CHAT（UsageRecordSink.SOURCE_*） */
    @Column(length = 16, nullable = false)
    private String source;

    /** 会话/问答短码 id（可跳详情；不设外键，源行删除后账本保留） */
    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 创建人快照（owner 强制过滤键） */
    @Column(length = 64)
    private String owner;

    /** 模型快照（CLI result 帧不带 model，从会话实体列取；空 = 默认模型） */
    @Column(length = 64)
    private String model;

    /** 回合耗时（速度 = output / durationMs 的分母） */
    private Long durationMs;

    private Long inputTokens;

    private Long outputTokens;

    private Long cacheReadTokens;

    private Long cacheCreationTokens;

    private Double costUsd;

    /** 回合完成时刻（= 回调时刻；请求日志排序与按小时分桶都靠它） */
    @Column(nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }

    public void setSource(String source) { this.source = source; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getOwner() { return owner; }

    public void setOwner(String owner) { this.owner = owner; }

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }

    public Long getDurationMs() { return durationMs; }

    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Long getInputTokens() { return inputTokens; }

    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }

    public Long getOutputTokens() { return outputTokens; }

    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }

    public Long getCacheReadTokens() { return cacheReadTokens; }

    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public Long getCacheCreationTokens() { return cacheCreationTokens; }

    public void setCacheCreationTokens(Long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }

    public Double getCostUsd() { return costUsd; }

    public void setCostUsd(Double costUsd) { this.costUsd = costUsd; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
