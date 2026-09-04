package com.devmind.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 通知通道配置（CAP-06 FR-03 通道插件化）：启用开关 + 分级阈值 + 通道专属配置。
 */
@Entity
@Table(name = "notification_channels")
public class NotificationChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ws / log / bark / wecom（通道 SPI 的 code） */
    @Column(length = 32, unique = true)
    private String code;

    @Column(length = 64)
    private String name;

    private boolean enabled;

    /** 本通道可推送的最低级别：P0/P1/P2（低于该级 SKIPPED） */
    @Column(length = 8)
    private String levelThreshold;

    /** 通道专属配置 JSON，如 bark 的 {"server":"https://api.day.app","key":""} */
    @Lob
    @Column(length = 16_777_216)
    private String configJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLevelThreshold() { return levelThreshold; }
    public void setLevelThreshold(String levelThreshold) { this.levelThreshold = levelThreshold; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
