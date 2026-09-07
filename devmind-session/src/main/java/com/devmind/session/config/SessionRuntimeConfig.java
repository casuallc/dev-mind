package com.devmind.session.config;

import com.devmind.common.agent.runtime.CliEventParser;
import com.devmind.common.agent.runtime.CliProcessLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-30：CLI 接触点（common.agent.runtime 的纯工具类）的 Spring 装配。
 * 无状态线程安全，chat（CAP-30）与 session 复用同组 Bean。
 */
@Configuration
public class SessionRuntimeConfig {

    @Bean
    public CliProcessLauncher cliProcessLauncher(SessionProperties props, ObjectMapper mapper) {
        return new CliProcessLauncher(props.toRuntimeSettings(), mapper);
    }

    @Bean
    public CliEventParser cliEventParser(ObjectMapper mapper, SessionProperties props) {
        return new CliEventParser(mapper, props.toRuntimeSettings());
    }
}
