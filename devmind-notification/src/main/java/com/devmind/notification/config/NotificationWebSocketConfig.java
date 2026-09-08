package com.devmind.notification.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.notification.controller.NotificationWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/notifications/stream 通知实时流（原生 WebSocket，与 /ws/sessions/{id} 同栈）。
 */
@Configuration
@EnableWebSocket
public class NotificationWebSocketConfig implements WebSocketConfigurer {

    private final NotificationWsHandler notificationWsHandler;
    private final CorsProperties corsProperties;

    public NotificationWebSocketConfig(NotificationWsHandler notificationWsHandler, CorsProperties corsProperties) {
        this.notificationWsHandler = notificationWsHandler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWsHandler, "/ws/notifications/stream")
                .setAllowedOrigins(corsProperties.originsArray());
    }
}
