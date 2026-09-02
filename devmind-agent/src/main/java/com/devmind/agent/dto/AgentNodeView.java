package com.devmind.agent.dto;

import com.devmind.agent.model.AgentNodeEntity;

import java.time.Instant;

/** 节点视图（不含 token 哈希）。 */
public record AgentNodeView(Long id, String name, String status, String os, String labels,
                            String capabilities, String runnerVersion,
                            Instant lastHeartbeatAt, Instant createdAt) {

    public static AgentNodeView from(AgentNodeEntity e) {
        return new AgentNodeView(e.getId(), e.getName(), e.getStatus(), e.getOs(), e.getLabels(),
                e.getCapabilities(), e.getRunnerVersion(), e.getLastHeartbeatAt(), e.getCreatedAt());
    }
}
