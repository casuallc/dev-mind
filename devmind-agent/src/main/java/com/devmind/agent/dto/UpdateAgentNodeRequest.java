package com.devmind.agent.dto;

import java.util.List;

/**
 * CAP-34 FR-07 更新节点请求。labels 逗号分隔（null/空串 = 清空）；
 * runner 侧 agent.properties 配置了 labels 时，下一次 hello 会用配置值覆盖此处编辑（D4 权威源约定）。
 *
 * <p>CAP-43：proxyUrl 节点外网代理（http(s)://host:port，禁 userinfo；null/空串 = 清空关闭），
 * proxyScopes 生效范围 CSV（子集 git/claude/exec；配了代理但空 = 默认 git）。</p>
 *
 * <p>CAP-65：fileRoots 文件访问根目录白名单（节点侧绝对路径列表，服务端 DB 权威、随 file 帧
 * 全量下发）。null = 不动；空数组 = 清空（文件浏览不可用）；非空逐条校验绝对路径、≤16 条、
 * 单条 ≤240 字符。</p>
 */
public record UpdateAgentNodeRequest(String labels, String proxyUrl, String proxyScopes,
                                     List<String> fileRoots) {
}
