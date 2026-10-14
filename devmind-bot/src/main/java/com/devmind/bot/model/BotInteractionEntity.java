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
 * bot 交互日志（CAP-75 FR-13）：底座处理过的每条事件落一行
 * （含 DEDUPED/REJECTED），群内未 @ 消息不落库（噪音）。
 * 出入站文本截断落库（入 2000 / 出 4000），完整内容永远以平台会话为准。
 */
@Entity
@Table(name = "bot_interactions", indexes =
        @Index(name = "idx_bot_interactions_channel_created", columnList = "channel, created_at"))
public class BotInteractionEntity {

    /** 处理成功 */
    public static final String STATUS_OK = "OK";
    /** 处理抛异常（DevMindException/未知） */
    public static final String STATUS_FAILED = "FAILED";
    /** event_id 去重命中，未执行 */
    public static final String STATUS_DEDUPED = "DEDUPED";
    /** FR-12 门禁拒绝（敏感操作未绑定个人身份） */
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台 code（feishu/wecom/…） */
    @Column(nullable = false, length = 32)
    private String channel;

    /** 平台侧会话 id */
    @Column(nullable = false, length = 128)
    private String externalChatId;

    /** p2p | group */
    @Column(length = 16)
    private String chatType;

    /** 发送者平台侧 id（飞书 open_id） */
    @Column(length = 128)
    private String senderId;

    /** 实际执行身份（四级链命中结果；DEDUPED 行为空） */
    @Column(length = 64)
    private String actor;

    /** 指令名（help/bind/…）| QA（裸文本问答）| DEDUPED */
    @Column(length = 16)
    private String command;

    /** 入站文本（截断 2000） */
    @Column(length = 2000)
    private String inputText;

    /** 出站回复（截断 4000） */
    @Column(length = 4000)
    private String replyText;

    /** OK / FAILED / DEDUPED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 512)
    private String error;

    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getReplyText() { return replyText; }
    public void setReplyText(String replyText) { this.replyText = replyText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
