package com.devmind.project.model;

import java.util.List;

/**
 * 项目（MVP：来自配置预置；CAP-02 落地后来自项目表）。
 *
 * @param agentNodeId CAP-21 默认执行节点 id（null = 本机）
 */
public record Project(String id, String name, String repoPath, String baseBranch, List<String> tags,
                      String agentNodeId) {
}
