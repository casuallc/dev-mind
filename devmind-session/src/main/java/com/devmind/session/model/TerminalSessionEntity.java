package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * CAP-115 terminal_sessions 表：终端会话（一次「打开终端工作区」即一条记录）。
 * state 机：RUNNING → SUSPENDED（90s 无心跳/节点离线，工作区保留可恢复）/ CLOSED（主动关闭）。
 */
@Entity
@Table(name = "terminal_sessions", indexes = {
        @Index(name = "idx_term_session_ws", columnList = "workspace_id")
})
public class TerminalSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** 所属终端工作区（terminal_workspaces.id） */
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** 操作人用户名 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** RUNNING / SUSPENDED / CLOSED */
    @Column(length = 16)
    @ColumnDefault("'RUNNING'")
    private String state = "RUNNING";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
