package com.devmind.session.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentEventListener;
import com.devmind.common.agent.WorkspaceStatusListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CAP-21 节点事件桥：实现 common 的 {@link AgentEventListener} SPI，由 devmind-agent
 * 以 ObjectProvider 探测注入，把 runner 上行帧路由进 {@link SessionManagerService}。
 * （SPI 在 common、实现在此，两模块互不设依赖边。）
 *
 * <p>CAP-54：兼实现 {@link WorkspaceStatusListener}——workspace_status 帧（git 变更快照）
 * 走旁路缓存/推送，不进会话事件流。</p>
 */
@Component
public class RemoteAgentBridge implements AgentEventListener, WorkspaceStatusListener {

    private final SessionManagerService service;

    public RemoteAgentBridge(SessionManagerService service) {
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

    @Override
    public void onWorkspaceStatus(String nodeId, String sessionId, Map<String, Object> snapshot) {
        service.onWorkspaceStatus(nodeId, sessionId, snapshot);
    }
}
