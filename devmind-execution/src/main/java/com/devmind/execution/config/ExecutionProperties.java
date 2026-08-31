package com.devmind.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.execution 配置：本地执行的 shell、单步超时。
 * 自 devmind.build 同名配置迁移（P0-1 统一执行底座），build/deploy/test/release 共用。
 */
@ConfigurationProperties(prefix = "devmind.execution")
public class ExecutionProperties {

    /** 本地执行用的 shell 解释器（Windows 下为 Git Bash 的 bash） */
    private String shell = "bash";

    /** 单步骤超时（毫秒），超时 kill 进程；默认 30 分钟 */
    private long stepTimeoutMs = 30 * 60 * 1000L;

    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
    public long getStepTimeoutMs() { return stepTimeoutMs; }
    public void setStepTimeoutMs(long stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; }
}
