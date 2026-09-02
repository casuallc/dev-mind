package com.devmind.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 心跳超时巡检需要调度；独立配置类不侵启动类（多模块重复 @EnableScheduling 幂等）。
 */
@Configuration
@EnableScheduling
public class AgentSchedulingConfig {
}
