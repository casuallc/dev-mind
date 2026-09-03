package com.devmind.agent.dto;

import com.devmind.agent.model.AgentNodeEntity;

import java.time.Instant;

/** 节点视图（不含 token 哈希）。isDefault = 平台默认执行节点（FR-03）。 */
public record AgentNodeView(Long id, String name, String status, String os, String labels,
                            String capabilities, String runnerVersion, boolean isDefault,
                            Instant lastHeartbeatAt, Instant createdAt) {

    public static AgentNodeView from(AgentNodeEntity e) {
        return new AgentNodeView(e.getId(), e.getName(), e.getStatus(), e.getOs(), e.getLabels(),
                e.getCapabilities(), e.getRunnerVersion(), e.isDefault(),
                e.getLastHeartbeatAt(), e.getCreatedAt());
    }
}
