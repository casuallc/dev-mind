package com.devmind.worklog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CAP-28 调度开关（刻意独立成配置类、不放在启动类上——同 JiraSyncSchedulingConfig 约定）。
 */
@Configuration
@EnableScheduling
public class WorklogSchedulingConfig {
}
