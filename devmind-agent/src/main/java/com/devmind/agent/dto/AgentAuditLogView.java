package com.devmind.agent.dto;

import com.devmind.agent.model.AgentAuditLogEntity;

import java.time.Instant;

/** 节点审计日志视图（CAP-71）。nodeId/nodeName 在 REJECT、代理未命中规则时为空。 */
public record AgentAuditLogView(Long id, Instant createdAt, String category, String event,
                                Long nodeId, String nodeName, String actor, String remoteAddr,
                                String target, String result, Long bytesUp, Long bytesDown,
                                Long durationMs, String detail) {

    public static AgentAuditLogView from(AgentAuditLogEntity e) {
        return new AgentAuditLogView(e.getId(), e.getCreatedAt(), e.getCategory(), e.getEvent(),
                e.getNodeId(), e.getNodeName(), e.getActor(), e.getRemoteAddr(),
                e.getTarget(), e.getResult(), e.getBytesUp(), e.getBytesDown(),
                e.getDurationMs(), e.getDetail());
    }
}
