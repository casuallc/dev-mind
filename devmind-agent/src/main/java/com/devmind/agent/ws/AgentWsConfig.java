package com.devmind.agent.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/agent（runner 反向接入，原生 WebSocket；@EnableWebSocket 由 session 模块的配置开启，
 * 多个 WebSocketConfigurer 会全部被收集）。
 */
@Configuration
public class AgentWsConfig implements WebSocketConfigurer {

    private final AgentNodeWsHandler handler;

    public AgentWsConfig(AgentNodeWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // runner 非浏览器客户端，无 Origin 概念
        registry.addHandler(handler, "/ws/agent").setAllowedOrigins("*");
    }
}
