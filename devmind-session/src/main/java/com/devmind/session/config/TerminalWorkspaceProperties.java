package com.devmind.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.terminal.* — CAP-115 终端工作区配置（闲置治理）。
 * 由 DevMindApplication 的 @ConfigurationPropertiesScan 自动注册。
 */
@ConfigurationProperties(prefix = "devmind.terminal")
public class TerminalWorkspaceProperties {

    /** 闲置提醒阈值（天）：OPEN 工作区 last_active_at 超过该值 → 站内提醒（默认 3 天，0=关闭提醒） */
    private int idleRemindDays = 3;

    /** 同一工作区两次提醒的最小间隔（小时），防每小时巡检重复轰炸（默认 24） */
    private int remindIntervalHours = 24;

    public int getIdleRemindDays() { return idleRemindDays; }
    public void setIdleRemindDays(int idleRemindDays) { this.idleRemindDays = idleRemindDays; }
    public int getRemindIntervalHours() { return remindIntervalHours; }
    public void setRemindIntervalHours(int remindIntervalHours) { this.remindIntervalHours = remindIntervalHours; }
}
