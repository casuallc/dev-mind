package com.devmind.agent.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 注册 /ws/agent（runner 反向接入，原生 WebSocket；@EnableWebSocket 由 session 模块的配置开启，
 * 多个 WebSocketConfigurer 会全部被收集）。
 */
@Configuration
public class AgentWsConfig implements WebSocketConfigurer {

    private final AgentNodeWsHandler handler;
    private final AgentTunnelWsHandler tunnelHandler;

    public AgentWsConfig(AgentNodeWsHandler handler, AgentTunnelWsHandler tunnelHandler) {
        this.handler = handler;
        this.tunnelHandler = tunnelHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // runner 非浏览器客户端，无 Origin 概念
        registry.addHandler(handler, "/ws/agent").setAllowedOrigins("*");
        // CAP-70：专用隧道通道（二进制流帧），独立于控制通道——大库 clone 的字节流不占指令通道
        registry.addHandler(tunnelHandler, "/ws/agent-tunnel").setAllowedOrigins("*");
    }

    /**
     * CAP-54：放大 WS 收帧缓冲——workspace_query_ack 可携带 ≤256KB 的文件内容/diff，
     * 容器默认 8KB 会直接断开连接。容器级设置对本应用全部 WS 端点生效（放大上限无副作用，
     * 缓冲按消息实际大小分配）。
     */
    @Bean
    public ServletServerContainerFactoryBean wsContainerFactory() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }
}
