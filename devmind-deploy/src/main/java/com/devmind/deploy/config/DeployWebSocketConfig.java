package com.devmind.deploy.config;

import com.devmind.deploy.ws.DeployWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/deployments/** 部署实时流（原生 WebSocket，与构建/通知/会话同栈）。
 */
@Configuration
@EnableWebSocket
public class DeployWebSocketConfig implements WebSocketConfigurer {

    private final DeployWsHandler deployWsHandler;

    public DeployWebSocketConfig(DeployWsHandler deployWsHandler) {
        this.deployWsHandler = deployWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deployWsHandler, "/ws/deployments/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }
}
