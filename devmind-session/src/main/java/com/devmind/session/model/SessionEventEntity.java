package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * session_events 表：持久化事件流（历史审计/回放）。批量异步落库。
 */
@Entity
@Table(name = "session_events", indexes = @Index(name = "idx_session_seq", columnList = "session_id, seq"))
public class SessionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 32)
    private String sessionId;

    private Long seq;

    @Column(length = 32)
    private String type;

    @Lob
    private String content;

    @Column(length = 8)
    private String source;

    /** 结构化负载（tool_use 名称/参数、permission_request 的 requestId/options、result 的 isError 等），JSON 文本。 */
    @Lob
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
