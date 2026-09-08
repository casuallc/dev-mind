package com.devmind.chat.config;

import com.devmind.chat.controller.ChatWsHandler;
import com.devmind.common.security.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/chats/{id} 实时流（CAP-30；@EnableWebSocket 由 session 模块的配置开启，
 * 多个 WebSocketConfigurer 会全部被收集——照 AgentWsConfig 模式）。
 */
@Configuration
public class ChatWsConfig implements WebSocketConfigurer {

    private final ChatWsHandler handler;
    private final CorsProperties corsProperties;

    public ChatWsConfig(ChatWsHandler handler, CorsProperties corsProperties) {
        this.handler = handler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chats/{id}")
                .setAllowedOrigins(corsProperties.originsArray());
    }
}
