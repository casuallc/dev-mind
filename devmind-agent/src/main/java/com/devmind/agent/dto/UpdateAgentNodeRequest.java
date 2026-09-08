package com.devmind.agent.dto;

/**
 * CAP-34 FR-07 更新节点请求（当前仅 labels，逗号分隔；null/空串 = 清空）。
 * 注意：runner 侧 agent.properties 配置了 labels 时，下一次 hello 会用配置值覆盖此处编辑（D4 权威源约定）。
 */
public record UpdateAgentNodeRequest(String labels) {
}
