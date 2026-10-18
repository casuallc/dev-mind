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
 * 对话轮审计（CAP-76 FR-14/FR-30）。列名用 {@code trigger_type}，不用 MySQL 保留字 trigger。
 */
@Entity
@Table(name = "copilot_turns", indexes = {
        @Index(name = "idx_copilot_turns_created", columnList = "created_at"),
        @Index(name = "idx_copilot_turns_actor_created", columnList = "actor,created_at")
})
public class CopilotTurnEntity {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";

    public static final String TRIGGER_USER = "USER";
    public static final String TRIGGER_CONFIRM = "CONFIRM";
    public static final String TRIGGER_CARD_ACTION = "CARD_ACTION";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "conversation_id", length = 32)
    private String conversationId;

    @Column(length = 32)
    private String channel;

    @Column(name = "external_chat_id", length = 128)
    private String externalChatId;

    @Column(name = "sender_id", length = 128)
    private String senderId;

    @Column(length = 64)
    private String actor;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Column(name = "user_text", length = 4000)
    private String userText;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "assistant_text", length = 16_777_216)
    private String assistantText;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType = TRIGGER_USER;

    @Column(length = 128)
    private String model;

    @Column(name = "endpoint_id", length = 64)
    private String endpointId;

    @Column(name = "tool_mode", length = 16)
    private String toolMode;

    @Column(nullable = false)
    private int steps;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(length = 2000)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
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
    public String getUserText() { return userText; }
    public void setUserText(String userText) { this.userText = userText; }
    public String getAssistantText() { return assistantText; }
    public void setAssistantText(String assistantText) { this.assistantText = assistantText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public String getToolMode() { return toolMode; }
    public void setToolMode(String toolMode) { this.toolMode = toolMode; }
    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
