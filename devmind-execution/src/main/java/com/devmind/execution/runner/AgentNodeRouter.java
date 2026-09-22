package com.devmind.execution.runner;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
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
        requireProtocol(nodeId, AgentProtocol.EXEC_FRAMES, "exec");
    }

    /**
     * CAP-56：节点在线且协议支持 exec 帧携带执行包（v14+）——决策实验室的评测/微调需要它。
     *
     * <p>在<b>触发阶段</b>查而不是等 exec 时再炸：运行是在后台线程里跑的，那时拒绝只会留下一条
     * FAILED 记录和一串"协议版本过低"的日志，而人是在页面上按的按钮——按钮那一刻就该知道
     * 节点不够新。判据与 {@code AgentConnectionRegistry.exec} 内的门控同源（同一个版本常量）。</p>
     */
    public void requireBundleCapable(String nodeId) {
        requireProtocol(nodeId, AgentProtocol.EXEC_BUNDLE, "决策实验室评测/微调（执行包由 runner 拉取物化）");
    }

    /** 在线 + 协议版本双关：两处判据只写一份，离线/过低的报错才不会各说各话 */
    private void requireProtocol(String nodeId, int minVersion, String what) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        if (!connector.isOnline(nodeId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + nodeId);
        }
        if (!connector.supports(nodeId, minVersion)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（" + what + " 需 v" + minVersion
                            + "+），请到节点页升级 runner");
        }
    }
}
