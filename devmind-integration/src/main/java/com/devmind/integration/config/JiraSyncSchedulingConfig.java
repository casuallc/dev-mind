package com.devmind.integration.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CAP-19 调度入口：全仓库首个 @Scheduled 使用点（Jira 轮询同步）。
 * 独立 @Configuration 自持 @EnableScheduling，不侵入启动类；其他模块不受影响。
 */
@Configuration
@EnableScheduling
public class JiraSyncSchedulingConfig {
}
