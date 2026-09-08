package com.devmind.session.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.session.controller.SessionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/sessions/{id} 实时流（原生 WebSocket，非 STOMP）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWsHandler sessionWsHandler;
    private final CorsProperties corsProperties;

    public WebSocketConfig(SessionWsHandler sessionWsHandler, CorsProperties corsProperties) {
        this.sessionWsHandler = sessionWsHandler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessionWsHandler, "/ws/sessions/{id}")
                .setAllowedOrigins(corsProperties.originsArray());
    }
}
