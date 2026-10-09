package com.devmind.agent.dto;

/** CAP-70 FR-08：节点隧道在线状态（GET /api/egress-rules/status） */
public record EgressTunnelStatusView(
        Long nodeId,
        String nodeName,
        String nodeStatus,
        Integer protocolVersion,
        boolean supportsTunnel,
        boolean tunnelOnline) {
}
