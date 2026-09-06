package com.devmind.agent.dto;

import com.devmind.agent.model.AgentConnLogEntity;

import java.time.Instant;

/** 节点连接流水视图。nodeId/nodeName 在 REJECT 时为空。 */
public record AgentConnLogView(Long id, Long nodeId, String nodeName, String event,
                               String remoteAddr, String detail, Instant createdAt) {

    public static AgentConnLogView from(AgentConnLogEntity e) {
        return new AgentConnLogView(e.getId(), e.getNodeId(), e.getNodeName(), e.getEvent(),
                e.getRemoteAddr(), e.getDetail(), e.getCreatedAt());
    }
}
