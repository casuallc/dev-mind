package com.devmind.bot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * bot 会话绑定（CAP-75 FR-07）：平台群/私聊 ↔ 平台问答会话 + 项目。
 * 多 bot 平台共表，唯一约束 (channel, external_chat_id) 天然隔离。
 */
@Entity
@Table(name = "bot_bindings", uniqueConstraints =
        @UniqueConstraint(columnNames = {"channel", "external_chat_id"}))
public class BotBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台 code（feishu/wecom/…） */
    @Column(nullable = false, length = 32)
    private String channel;

    /** 平台侧会话 id（飞书 chat_id） */
    @Column(nullable = false, length = 128)
    private String externalChatId;

    /** p2p | group */
    @Column(length = 16)
    private String chatType;

    /** 首个触发绑定的发送者（平台侧 id） */
    @Column(length = 128)
    private String ownerSenderId;

    /** 平台问答会话 id（CAP-30 chat；懒创建，/new 清空重开） */
    @Column(length = 64)
    private String chatSessionId;

    /** /bind 绑定的项目 id */
    @Column(length = 64)
    private String projectId;

    /** 操作身份（可空 = 该 channel 配置的 defaultActor） */
    @Column(length = 64)
    private String actor;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getOwnerSenderId() { return ownerSenderId; }
    public void setOwnerSenderId(String ownerSenderId) { this.ownerSenderId = ownerSenderId; }
    public String getChatSessionId() { return chatSessionId; }
    public void setChatSessionId(String chatSessionId) { this.chatSessionId = chatSessionId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
