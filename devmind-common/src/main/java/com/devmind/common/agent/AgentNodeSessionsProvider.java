package com.devmind.common.agent;

import java.time.Instant;
import java.util.List;

/**
 * CAP-21 FR-09 节点活跃会话查询 SPI（agent 模块 ← session/chat 模块：强制升级前
 * 向管理员展示「该节点上有哪些活跃会话」）。接口定义在 common（同 AgentNodeConnector 先例），
 * 实现方为各会话持有方（devmind-session / devmind-chat），消费方 devmind-agent 以
 * {@code List<AgentNodeSessionsProvider>} 聚合注入——未装配任何实现时清单为空。
 */
public interface AgentNodeSessionsProvider {

    /** 指定节点上处于活动状态（RUNNING/WAITING_INPUT/WAITING_AUTH）的会话清单。 */
    List<ActiveSessionInfo> activeSessions(String nodeId);

    /**
     * @param kind "SESSION"（项目开发会话）/ "CHAT"（通用问答）
     */
    record ActiveSessionInfo(String sessionId, String kind, String title,
                             String status, String createdBy, Instant createdAt) {
    }
}
