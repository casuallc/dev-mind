package com.devmind.agent.dto;

/**
 * CAP-34 FR-07 更新节点请求。labels 逗号分隔（null/空串 = 清空）；
 * runner 侧 agent.properties 配置了 labels 时，下一次 hello 会用配置值覆盖此处编辑（D4 权威源约定）。
 *
 * <p>CAP-43：proxyUrl 节点外网代理（http(s)://host:port，禁 userinfo；null/空串 = 清空关闭），
 * proxyScopes 生效范围 CSV（子集 git/claude/exec；配了代理但空 = 默认 git）。</p>
 */
public record UpdateAgentNodeRequest(String labels, String proxyUrl, String proxyScopes) {
}
