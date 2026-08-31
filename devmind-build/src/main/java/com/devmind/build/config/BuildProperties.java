package com.devmind.build.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.build 配置：本地执行的 shell、单步超时、默认并发上限。
 */
@ConfigurationProperties(prefix = "devmind.build")
public class BuildProperties {

    /** 本地执行用的 shell 解释器（Windows 下为 Git Bash 的 bash） */
    private String shell = "bash";

    /** 单步骤超时（毫秒），超时 kill 进程；默认 30 分钟 */
    private long stepTimeoutMs = 30 * 60 * 1000L;

    /** 新建配置时的默认并发上限 */
    private int defaultConcurrency = 1;

    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
    public long getStepTimeoutMs() { return stepTimeoutMs; }
    public void setStepTimeoutMs(long stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; }
    public int getDefaultConcurrency() { return defaultConcurrency; }
    public void setDefaultConcurrency(int defaultConcurrency) { this.defaultConcurrency = defaultConcurrency; }
}
