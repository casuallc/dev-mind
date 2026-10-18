package com.devmind.copilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 写操作确认单（CAP-76 FR-13）。
 *
 * <p>{@code args_json} 是执行权威快照：规范化后的原始参数，不掩码、不截断（仅 ADMIN 审计可见）。</p>
 */
@Entity
@Table(name = "copilot_actions", indexes = {
        @Index(name = "idx_copilot_actions_short_code", columnList = "short_code"),
        @Index(name = "idx_copilot_actions_status_expires", columnList = "status,expires_at"),
        @Index(name = "idx_copilot_actions_sender_scope",
                columnList = "sender_id,channel,external_chat_id,status")
})
public class CopilotActionEntity {

    public static final String PENDING = "PENDING";
    public static final String EXECUTING = "EXECUTING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "turn_id", nullable = false, length = 32)
    private String turnId;

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

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "args_json", length = 16_777_216)
    private String argsJson;

    @Column(length = 16)
    private String risk;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "preview_text", length = 16_777_216)
    private String previewText;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "short_code", length = 12)
    private String shortCode;

    @Column(name = "card_message_id", length = 128)
    private String cardMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "result_json", length = 16_777_216)
    private String resultJson;

    @Column(length = 2000)
    private String error;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
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
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getPreviewText() { return previewText; }
    public void setPreviewText(String previewText) { this.previewText = previewText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    public String getCardMessageId() { return cardMessageId; }
    public void setCardMessageId(String cardMessageId) { this.cardMessageId = cardMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
