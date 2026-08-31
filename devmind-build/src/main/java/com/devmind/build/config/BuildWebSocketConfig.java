package com.devmind.build.config;

import com.devmind.build.ws.BuildLogWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/builds/** 构建日志实时流（原生 WebSocket，与通知/会话同栈）。
 */
@Configuration
@EnableWebSocket
public class BuildWebSocketConfig implements WebSocketConfigurer {

    private final BuildLogWsHandler buildLogWsHandler;

    public BuildWebSocketConfig(BuildLogWsHandler buildLogWsHandler) {
        this.buildLogWsHandler = buildLogWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(buildLogWsHandler, "/ws/builds/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }
}
