package com.devmind.test.config;

import com.devmind.test.ws.TestRunWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/test-runs/** 测试实时流（与构建/部署同栈）。
 */
@Configuration
@EnableWebSocket
public class TestWebSocketConfig implements WebSocketConfigurer {

    private final TestRunWsHandler testRunWsHandler;

    public TestWebSocketConfig(TestRunWsHandler testRunWsHandler) {
        this.testRunWsHandler = testRunWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(testRunWsHandler, "/ws/test-runs/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }
}
