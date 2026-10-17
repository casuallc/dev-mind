package com.devmind.bot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话终态推送订阅（CAP-75 FR-20）：bot 起会话时登记一行，
 * 会话结束（session.completed）命中即向来源聊天推一条终态消息，**推完删行**（一次性）。
 *
 * <p>session_id 唯一：一个会话只服务一个来源聊天；7 天前残留由 FR-24 清理任务顺带删。</p>
 */
@Entity
@Table(name = "bot_session_watches", indexes =
        @Index(name = "idx_bot_session_watches_created", columnList = "created_at"))
public class BotSessionWatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台会话 id（唯一；推送命中即删） */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /** 平台 code（feishu/wecom/…） */
    @Column(nullable = false, length = 32)
    private String channel;

    /** 平台侧会话 id（飞书 chat_id）——终态消息推到这里 */
    @Column(name = "external_chat_id", nullable = false, length = 128)
    private String externalChatId;

    /** 起会话时的任务描述截断（推送文案用；会话已删时兜底展示） */
    @Column(name = "task_spec", length = 200)
    private String taskSpec;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public String getTaskSpec() { return taskSpec; }
    public void setTaskSpec(String taskSpec) { this.taskSpec = taskSpec; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
