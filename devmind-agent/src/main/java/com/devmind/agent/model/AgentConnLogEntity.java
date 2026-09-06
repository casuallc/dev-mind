package com.devmind.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * agent_conn_logs 表：runner 接入/拒绝/断线流水（CAP-21）。
 * 拒绝时 token 解析不出节点，nodeId/nodeName 为空、只有来源地址；nodeName 做快照，节点删除后日志仍可读。
 */
@Entity
@Table(name = "agent_conn_logs", indexes = @Index(name = "idx_conn_log_created", columnList = "created_at"))
public class AgentConnLogEntity {

    /** 接入成功 */
    public static final String EVENT_CONNECT = "CONNECT";
    /** 接入被拒绝（token 无效或节点已禁用） */
    public static final String EVENT_REJECT = "REJECT";
    /** 连接断开 */
    public static final String EVENT_DISCONNECT = "DISCONNECT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联节点（REJECT 时为空） */
    @Column(name = "node_id")
    private Long nodeId;

    /** 节点名快照（节点删除后日志仍可读） */
    @Column(name = "node_name", length = 128)
    private String nodeName;

    /** CONNECT / REJECT / DISCONNECT */
    @Column(length = 16, nullable = false)
    private String event;

    /** 远端地址（IP:端口） */
    @Column(name = "remote_addr", length = 64)
    private String remoteAddr;

    /** 补充说明（如拒绝原因、断线关闭码） */
    @Column(length = 512)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
