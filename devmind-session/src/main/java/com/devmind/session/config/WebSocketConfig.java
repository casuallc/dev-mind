package com.devmind.session.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.session.controller.SessionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * 注册 /ws/sessions/{id} 实时流（原生 WebSocket，非 STOMP）。
 *
 * <p>CAP-50：套 {@link ConcurrentWebSocketSessionDecorator}（发送缓冲 + 超时踢连接），与
 * /ws/chats 同规格。{@code SessionWsHandler.send} 是在**发布者线程**上阻塞式 sendMessage，而
 * runner 会话的发布者就是节点入站 WS 线程——慢浏览器会一路反压到节点连接，把状态流转
 * （result → WAITING_INPUT）一起堵在后面。原判断「会话 WS 事件频率低」在 runner 会话开流式
 * 输出后已不成立，故补上。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 单次发送超时：超过即认为客户端不可用（连接被踢，避免阻塞发布线程） */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    /** 发送缓冲上限：慢客户端积压超过 512KB 直接踢掉，内存不跟着客户端走 */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final SessionWsHandler sessionWsHandler;
    private final CorsProperties corsProperties;

    public WebSocketConfig(SessionWsHandler sessionWsHandler, CorsProperties corsProperties) {
        this.sessionWsHandler = sessionWsHandler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(buffered(sessionWsHandler), "/ws/sessions/{id}")
                .setAllowedOrigins(corsProperties.originsArray());
    }

    /**
     * 把交给 handler 的会话换成带缓冲的装饰会话。事件推送用的是 handler 在
     * {@code afterConnectionEstablished} 里捕获的那个会话引用，故这里替换即覆盖热路径。
     */
    private static WebSocketHandler buffered(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                super.afterConnectionEstablished(new ConcurrentWebSocketSessionDecorator(
                        session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
            }
        };
    }
}
