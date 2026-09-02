package com.devmind.agent.dto;

/** 创建节点响应：token 明文仅此一次返回，服务端只存哈希。 */
public record IssuedNodeView(AgentNodeView node, String token) {
}
