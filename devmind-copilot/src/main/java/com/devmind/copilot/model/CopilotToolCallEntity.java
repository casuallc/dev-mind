package com.devmind.copilot.model;

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
 * 工具调用审计（CAP-76 FR-14/FR-28）：{@code args_json} 是脱敏截断副本；
 * 执行权威快照在 {@code copilot_actions.args_json}。
 */
@Entity
@Table(name = "copilot_tool_calls", indexes = {
        @Index(name = "idx_copilot_tool_calls_turn", columnList = "turn_id"),
        @Index(name = "idx_copilot_tool_calls_name_created", columnList = "tool_name,created_at"),
        @Index(name = "idx_copilot_tool_calls_action_step", columnList = "action_id,step_seq")
})
public class CopilotToolCallEntity {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    /** 计划步骤中间态（M8，FR-36）：步骤开始执行 / 等待作业终态。 */
    public static final String RUNNING = "RUNNING";
    public static final String WAITING = "WAITING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false, length = 32)
    private String turnId;

    @Column(name = "action_id", length = 32)
    private String actionId;

    /** 计划步骤序号（M8，FR-36）：submit_plan 的步骤行 1 起；非步骤行为 null。 */
    @Column(name = "step_seq")
    private Integer stepSeq;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "args_json", length = 16_777_216)
    private String argsJson;

    @Column(length = 16)
    private String risk;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "result_summary", length = 4000)
    private String resultSummary;

    @Column(length = 2000)
    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public Integer getStepSeq() { return stepSeq; }
    public void setStepSeq(Integer stepSeq) { this.stepSeq = stepSeq; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
