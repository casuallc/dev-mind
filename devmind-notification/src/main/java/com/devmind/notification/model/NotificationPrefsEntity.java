package com.devmind.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 通知偏好（CAP-06 FR-05 防打扰）：免打扰时段、按事件/实体的静默。
 * 单用户起步固定 userId="local"。
 */
@Entity
@Table(name = "notification_prefs")
public class NotificationPrefsEntity {

    @Id
    private String userId;

    /** 静默配置 JSON：{"eventTypes":["SESSION_DONE"],"entityIds":["abc123"]} */
    @Lob
    @Column(length = 16_777_216)
    private String mutesJson;

    /** 免打扰时段 "HH:mm"，如 23:00 ~ 07:30；为空表示不启用 */
    @Column(length = 8)
    private String quietStart;

    @Column(length = 8)
    private String quietEnd;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMutesJson() { return mutesJson; }
    public void setMutesJson(String mutesJson) { this.mutesJson = mutesJson; }
    public String getQuietStart() { return quietStart; }
    public void setQuietStart(String quietStart) { this.quietStart = quietStart; }
    public String getQuietEnd() { return quietEnd; }
    public void setQuietEnd(String quietEnd) { this.quietEnd = quietEnd; }
}
