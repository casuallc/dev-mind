package com.devmind.bot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交互日志保留期清理需要调度；独立配置类不侵启动类（多模块重复 @EnableScheduling 幂等）。
 * attachment / agent 同款。
 */
@Configuration
@EnableScheduling
public class BotSchedulingConfig {
}
