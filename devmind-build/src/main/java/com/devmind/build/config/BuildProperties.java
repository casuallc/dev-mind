package com.devmind.build.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.build 配置：默认并发上限。
 * 本地执行 shell/单步超时已迁移至 devmind.execution（P0-1 统一执行底座，见 ExecutionProperties）。
 */
@ConfigurationProperties(prefix = "devmind.build")
public class BuildProperties {

    /** 新建配置时的默认并发上限 */
    private int defaultConcurrency = 1;

    public int getDefaultConcurrency() { return defaultConcurrency; }
    public void setDefaultConcurrency(int defaultConcurrency) { this.defaultConcurrency = defaultConcurrency; }
}
