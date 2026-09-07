package com.devmind.chat.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentEventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-30 节点事件桥：实现 common 的 {@link AgentEventListener} SPI，由 devmind-agent
 * 广播回调，把 runner 上行帧路由进 {@link ChatManagerService}。
 * 未持有该 sessionId 的运行时时各方法自然 no-op（runtimes 查不到即忽略）。
 */
@Component
public class ChatAgentBridge implements AgentEventListener {

    private final ChatManagerService service;

    public ChatAgentBridge(ChatManagerService service) {
        this.service = service;
    }

    @Override
    public void onAgentEvent(String nodeId, AgentEventFrame frame) {
        service.onRemoteEvent(nodeId, frame);
    }

    @Override
    public void onAgentExit(String nodeId, String sessionId, int exitCode) {
        service.onRemoteExit(nodeId, sessionId, exitCode);
    }

    @Override
    public void onAgentHello(String nodeId, List<String> activeSessionIds) {
        service.onRemoteHello(nodeId, activeSessionIds);
    }

    @Override
    public void onAgentDisconnected(String nodeId) {
        service.onNodeDisconnected(nodeId);
    }
}
