package com.devmind.task.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CAP-80 调度入口：独立 @Configuration 自持 @EnableScheduling（同 JiraSyncSchedulingConfig
 * 先例），不侵启动类。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AsyncTaskProperties.class)
public class AsyncTaskSchedulingConfig {
}
