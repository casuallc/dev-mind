package com.devmind.agent.dto;

import com.devmind.agent.model.AgentNodeEntity;

import java.time.Instant;

/** 节点视图（不含 token 哈希）。isDefault = 平台默认执行节点（FR-03）；remoteAddr = 最近接入的 IP:端口；
 * workspaceBytes = 工作区磁盘占用（CAP-34 FR-05，旧 runner 未上报为 null）；
 * protocolVersion = WS 协议版本（FR-08，未上报按 v1 对待）；
 * toolchain = 探测到的工具链 JSON 对象串（FR-07，未上报为 null）。 */
public record AgentNodeView(Long id, String name, String status, String os, String labels,
                            String capabilities, String runnerVersion, boolean isDefault,
                            String remoteAddr, Instant lastHeartbeatAt, Instant createdAt,
                            Long workspaceBytes, Integer protocolVersion, String toolchain) {

    public static AgentNodeView from(AgentNodeEntity e) {
        return new AgentNodeView(e.getId(), e.getName(), e.getStatus(), e.getOs(), e.getLabels(),
                e.getCapabilities(), e.getRunnerVersion(), e.isDefault(), e.getRemoteAddr(),
                e.getLastHeartbeatAt(), e.getCreatedAt(), e.getWorkspaceBytes(), e.getProtocolVersion(),
                e.getToolchain());
    }
}
