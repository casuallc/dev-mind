package com.devmind.chat.config;

import com.devmind.chat.controller.ChatWsHandler;
import com.devmind.common.security.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * 注册 /ws/chats/{id} 实时流（CAP-30；@EnableWebSocket 由 session 模块的配置开启，
 * 多个 WebSocketConfigurer 会全部被收集——照 AgentWsConfig 模式）。
 *
 * <p>CAP-49：给问答会话套 {@link ConcurrentWebSocketSessionDecorator}（发送缓冲 + 超时踢连接）。
 * 模型执行体一轮会推上千条 {@code text_delta}，客户端读得慢时发送会阻塞事件推送线程，
 * 进而把上游 SSE 读取一起钉死（模型侧还在产，服务端却在等 WS）。装饰只包 /ws/chats。
 * （CAP-50 起 /ws/sessions 也套了同规格装饰——CLI 会话开流式输出后它的帧率与问答同级，
 * 当时「会话 WS 事件频率低」的判断已不成立；两处各自留一份是各能力自包含的取舍。）</p>
 */
@Configuration
public class ChatWsConfig implements WebSocketConfigurer {

    /** 单次发送超时：超过即认为客户端不可用（连接被踢，避免阻塞上游） */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    /** 发送缓冲上限：慢客户端积压超过 512KB 直接踢掉，内存不跟着客户端走 */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final ChatWsHandler handler;
    private final CorsProperties corsProperties;

    public ChatWsConfig(ChatWsHandler handler, CorsProperties corsProperties) {
        this.handler = handler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(buffered(handler), "/ws/chats/{id}")
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
