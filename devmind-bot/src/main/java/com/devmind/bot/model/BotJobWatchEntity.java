package com.devmind.bot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 作业终态推送订阅（CAP-75 FR-25）：CAP-76 智能体在 bot 聊天里受理异步作业
 * （构建/部署/测试）后登记一行，终态事件命中即向来源聊天推一条结果消息，
 * **推完删行**（一次性，口径同 FR-20 会话 watch）。
 *
 * <p>(entity_type, entity_id) 唯一：一个作业只服务一个来源聊天，重复登记覆盖旧行；
 * 7 天前残留由 FR-24 清理任务顺带删。</p>
 */
@Entity
@Table(name = "bot_job_watches",
        uniqueConstraints = @UniqueConstraint(name = "uk_bot_job_watches_entity",
                columnNames = {"entity_type", "entity_id"}),
        indexes = @Index(name = "idx_bot_job_watches_created", columnList = "created_at"))
public class BotJobWatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 实体类型（BUILD/DEPLOYMENT/TEST_RUN；SESSION 走 bot_session_watches 不入本表） */
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    /** 实体 id（构建号/部署号/测试运行号） */
    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    /** 平台 code（feishu/wecom/…） */
    @Column(nullable = false, length = 32)
    private String channel;

    /** 平台侧会话 id（飞书 chat_id）——终态消息推到这里 */
    @Column(name = "external_chat_id", nullable = false, length = 128)
    private String externalChatId;

    /** 受理时的操作名截断（推送文案用，如「触发构建」） */
    @Column(length = 200)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
