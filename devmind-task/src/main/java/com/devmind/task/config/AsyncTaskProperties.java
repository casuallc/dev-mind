package com.devmind.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-80 调度内核配置（devmind.task.*）。
 */
@ConfigurationProperties(prefix = "devmind.task")
public class AsyncTaskProperties {

    /** 主 tick 间隔（FR-06），默认 5s */
    private long tickDelayMs = 5_000L;

    /** 单 tick 任务认领上限（防重启后积压任务单 tick 全部起跑，R2） */
    private int tickMaxClaim = 50;

    /** 僵尸收割间隔（FR-09），默认 60s */
    private long staleReaperIntervalMs = 60_000L;

    /** 僵尸判定兜底上限：min(timeout_ms×2, zombieCapMs)，默认 2h */
    private long zombieThresholdCapMs = 2 * 60 * 60_000L;

    public long getTickDelayMs() { return tickDelayMs; }
    public void setTickDelayMs(long tickDelayMs) { this.tickDelayMs = tickDelayMs; }
    public int getTickMaxClaim() { return tickMaxClaim; }
    public void setTickMaxClaim(int tickMaxClaim) { this.tickMaxClaim = tickMaxClaim; }
    public long getStaleReaperIntervalMs() { return staleReaperIntervalMs; }
    public void setStaleReaperIntervalMs(long staleReaperIntervalMs) { this.staleReaperIntervalMs = staleReaperIntervalMs; }
    public long getZombieThresholdCapMs() { return zombieThresholdCapMs; }
    public void setZombieThresholdCapMs(long zombieThresholdCapMs) { this.zombieThresholdCapMs = zombieThresholdCapMs; }
}
