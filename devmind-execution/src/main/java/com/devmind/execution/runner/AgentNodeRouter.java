package com.devmind.execution.runner;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-36 节点调度（执行器共用）：executor=AGENT 的路由链——显式指定 > 项目默认节点 >
 * 平台默认节点 > 标签匹配兜底 > 皆无命中 409（对齐会话路由 CAP-34 FR-02/07 语义，无本机回落）。
 */
@Component
public class AgentNodeRouter {

    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    public AgentNodeRouter(ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.connectorProvider = connectorProvider;
    }

    /**
     * @param explicitNodeId       本次触发显式指定的节点（可空）
     * @param projectDefaultNodeId 项目默认节点（Project.agentNodeId，可空）
     * @param requiredLabels       标签要求（空 = 不门控）
     * @return 命中的在线节点 id
     */
    public String route(String explicitNodeId, String projectDefaultNodeId, List<String> requiredLabels) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        List<String> labels = requiredLabels == null ? List.of() : requiredLabels;
        if (explicitNodeId != null && !explicitNodeId.isBlank()) {
            String id = explicitNodeId.strip();
            if (!connector.nodeMatches(id, labels)) {
                throw new DevMindException(ErrorCode.CONFLICT, "指定节点 " + id + " 不满足标签要求: " + labels);
            }
            if (!connector.isOnline(id)) {
                throw new DevMindException(ErrorCode.CONFLICT, "指定节点不在线: " + id);
            }
            return id;
        }
        for (String candidate : new String[]{projectDefaultNodeId, connector.defaultNodeId()}) {
            if (candidate != null && !candidate.isBlank()
                    && connector.isOnline(candidate) && connector.nodeMatches(candidate, labels)) {
                return candidate;
            }
        }
        if (!labels.isEmpty()) {
            String picked = connector.pickNodeByLabels(labels);
            if (picked != null) {
                return picked;
            }
        }
        throw new DevMindException(ErrorCode.CONFLICT, labels.isEmpty()
                ? "无可用执行节点（无在线节点；请到 Agent 节点页启动 runner 或设置平台默认节点）"
                : "无满足标签的在线节点: " + labels);
    }

    /** 节点在线且协议支持 exec 帧（v3+）；不满足抛 409 带可操作建议。 */
    public void requireExecCapable(String nodeId) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        if (!connector.isOnline(nodeId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + nodeId);
        }
        if (!connector.supports(nodeId, com.devmind.common.agent.AgentProtocol.EXEC_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（exec 需 v"
                            + com.devmind.common.agent.AgentProtocol.EXEC_FRAMES + "+），请到节点页升级 runner");
        }
    }
}
