package com.devmind.session.runtime;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionState;

/**
 * CAP-21 远程会话运行时：会话进程在节点 runner 侧，本类经 {@link AgentNodeConnector}
 * 下发指令（input/authorize/finish/kill/suspend），并由 {@link #ingest} 接收 runner 回传的
 * 已解析事件驱动同一套状态机。事件 seq 在服务端重编（runner seq 不带上行，单连接有序到达）。
 */
public class RemoteSessionRuntime extends AbstractSessionRuntime {

    private final String nodeId;
    private final AgentNodeConnector connector;
    /** 节点当前是否在线（断线期间 alive 仍 true：进程在 runner 侧可能还活着，等对账）。 */
    private volatile boolean nodeOnline = true;

    public RemoteSessionRuntime(String id, String nodeId, AgentNodeConnector connector,
                                SessionEventSaver saver, RuntimeListener listener, SessionProperties props) {
        super(id, saver, listener, props);
        this.nodeId = nodeId;
        this.connector = connector;
    }

    public String nodeId() { return nodeId; }

    /** runner 回传事件入口（RemoteAgentBridge 路由至此）。 */
    public void ingest(AgentEventFrame frame) {
        publish(SessionEvent.of(nextSeq(), frame.type(), frame.content(), frame.source(),
                frame.timestamp(), frame.payload() == null ? java.util.Map.of() : frame.payload()));
    }

    /** 节点断线：不判 FAILED（进程可能还活着），打一条系统日志事件，等重连对账。 */
    public void noteDisconnected() {
        nodeOnline = false;
        publish(SessionEvent.of(nextSeq(), "log", "节点连接已断开，等待 runner 重连对账", "system"));
    }

    /** hello 对账：runner 侧该会话已不存在（runner 重启进程丢失）→ FAILED。 */
    public void markLost(String reason) {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        transition(SessionState.FAILED, reason);
        listener.onExit(id, -1, false, reason);
    }

    /** runner 重连：恢复在线标记。 */
    public void noteReconnected() {
        nodeOnline = true;
        publish(SessionEvent.of(nextSeq(), "log", "节点已重连，会话继续", "system"));
    }

    @Override
    protected void sendUserMessage(String text) {
        connector.sendInput(nodeId, id, text);
    }

    @Override
    protected void sendPermissionResult(String requestId, boolean accepted, String scope) {
        connector.sendAuthorize(nodeId, id, requestId, accepted, scope);
    }

    @Override
    protected void doFinish() {
        try {
            connector.sendFinish(nodeId, id);
        } catch (Exception e) {
            // 收尾路径宽松：节点离线时 finish 尽力下发，对账兜底
            org.slf4j.LoggerFactory.getLogger(RemoteSessionRuntime.class)
                    .warn("远程 finish 下发失败: session={} err={}", id, e.getMessage());
        }
    }

    @Override
    protected void destroyTree() {
        try {
            connector.sendKill(nodeId, id);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RemoteSessionRuntime.class)
                    .warn("远程 kill 下发失败: session={} err={}", id, e.getMessage());
        }
    }

    @Override
    protected boolean alive() {
        return !exitHandled.get();
    }

    public boolean isNodeOnline() { return nodeOnline; }
}
