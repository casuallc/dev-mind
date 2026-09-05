package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * worklog_user_settings 表（CAP-28）：个人开关——是否参与定时自动日报/周报。
 * 无此行的用户默认开启（调度按"有订阅或有条目"判定覆盖范围）。
 */
@Entity
@Table(name = "worklog_user_settings")
public class WorklogUserSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64, unique = true)
    private String userId;

    /** 每日定时生成日报草稿 */
    @Column(name = "auto_daily", nullable = false)
    private Boolean autoDaily = Boolean.TRUE;

    /** 每周一定时生成上周周报草稿 */
    @Column(name = "auto_weekly", nullable = false)
    private Boolean autoWeekly = Boolean.TRUE;

    /** 每日目标工时（分钟，可空；前端展示参考） */
    @Column(name = "daily_minutes_target")
    private Integer dailyMinutesTarget;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Boolean getAutoDaily() { return autoDaily; }
    public void setAutoDaily(Boolean autoDaily) { this.autoDaily = autoDaily; }
    public Boolean getAutoWeekly() { return autoWeekly; }
    public void setAutoWeekly(Boolean autoWeekly) { this.autoWeekly = autoWeekly; }
    public Integer getDailyMinutesTarget() { return dailyMinutesTarget; }
    public void setDailyMinutesTarget(Integer dailyMinutesTarget) { this.dailyMinutesTarget = dailyMinutesTarget; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
