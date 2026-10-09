package com.devmind.agent.service;

/**
 * CAP-70：隧道在线状态源（devmind-agent 内 AgentTunnelRegistry 实现）。
 * 规则 CRUD 与隧道传输解耦：规则服务只查询状态，不反向依赖传输层。
 */
public interface EgressTunnelStatus {

    /** 节点当前是否有活跃隧道连接（/ws/agent-tunnel） */
    boolean tunnelOnline(Long nodeId);
}
