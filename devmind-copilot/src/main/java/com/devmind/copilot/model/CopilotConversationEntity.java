package com.devmind.copilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 智能体会话（CAP-76 FR-08）：会话键 {@code (channel, external_chat_id, sender_id)}，
 * 群聊里每个人独立，避免权限上下文串号。
 */
@Entity
@Table(name = "copilot_conversations", uniqueConstraints =
        @UniqueConstraint(columnNames = {"channel", "external_chat_id", "sender_id"}))
public class CopilotConversationEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "external_chat_id", nullable = false, length = 128)
    private String externalChatId;

    @Column(name = "sender_id", nullable = false, length = 128)
    private String senderId;

    @Column(length = 64)
    private String actor;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "last_turn_at")
    private Instant lastTurnAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastTurnAt() { return lastTurnAt; }
    public void setLastTurnAt(Instant lastTurnAt) { this.lastTurnAt = lastTurnAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
