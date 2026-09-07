package com.devmind.chat.config;

import com.devmind.chat.controller.ChatWsHandler;
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

    public ChatWsConfig(ChatWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chats/{id}")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }
}
