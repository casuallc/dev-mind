package com.devmind.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.agent.* — 远程节点接入配置（CAP-21）。
 */
@ConfigurationProperties(prefix = "devmind.agent")
public class AgentProperties {

    /** 心跳超时（毫秒）：超过未收到任何帧（含 heartbeat）则判 OFFLINE */
    private long heartbeatTimeoutMs = 45_000;
    /** launch 指令等待 runner ack 的超时（毫秒） */
    private long launchAckTimeoutMs = 15_000;
    /** 离线巡检周期（毫秒） */
    private long watchdogMs = 15_000;

    public long getHeartbeatTimeoutMs() { return heartbeatTimeoutMs; }
    public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) { this.heartbeatTimeoutMs = heartbeatTimeoutMs; }
    public long getLaunchAckTimeoutMs() { return launchAckTimeoutMs; }
    public void setLaunchAckTimeoutMs(long launchAckTimeoutMs) { this.launchAckTimeoutMs = launchAckTimeoutMs; }
    public long getWatchdogMs() { return watchdogMs; }
    public void setWatchdogMs(long watchdogMs) { this.watchdogMs = watchdogMs; }
}
