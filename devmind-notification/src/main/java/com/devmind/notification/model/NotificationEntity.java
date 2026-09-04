package com.devmind.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 通知记录（CAP-06 FR-06 通知中心）。分级+去重+快捷动作+通道状态都挂在这。
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** P0/P1/P2（FR-02 分级） */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private NotificationLevel level;

    /** 事件类型：WAITING_INPUT / WAITING_AUTH / SESSION_DONE / SESSION_FAILED / SESSION_STARTED / … */
    @Column(length = 64)
    private String eventType;

    @Column(length = 200)
    private String title;

    @Lob
    @Column(length = 16_777_216)
    private String body;

    /** 关联实体类型：SESSION / PROJECT / DOC / … */
    @Column(length = 32)
    private String entityType;

    /** 关联实体 ID（如会话 ID）。 */
    @Column(length = 64)
    private String entityId;

    /** 快捷动作 JSON：[{"action":"authorize","label":"允许授权"},…]（FR-04） */
    @Lob
    @Column(length = 16_777_216)
    private String actions;

    /** 各通道发送结果 JSON：{"ws":"SENT","bark":"SKIPPED:未配置"…} */
    @Lob
    @Column(length = 16_777_216)
    private String channelStatus;

    private Instant readAt;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public NotificationLevel getLevel() { return level; }
    public void setLevel(NotificationLevel level) { this.level = level; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getActions() { return actions; }
    public void setActions(String actions) { this.actions = actions; }
    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
