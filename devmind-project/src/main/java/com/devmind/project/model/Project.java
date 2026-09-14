package com.devmind.project.model;

import java.util.List;

/**
 * 项目（MVP：来自配置预置；CAP-02 落地后来自项目表）。
 *
 * @param agentNodeId CAP-21 默认执行节点 id（null = 本机）
 * @param kind        CAP-41：项目种类（{@link ProjectEntity#KIND_NORMAL} /
 *                    {@link ProjectEntity#KIND_WORKLOG}）；WORKLOG = 工作日志空间
 *                    （runner 持久工作区，无仓库/分支语义）
 * @param ownerId     CAP-41：归属用户（WORKLOG 项目据此做 runner 目录隔离与懒创建查找）
 */
public record Project(String id, String name, String repoPath, String baseBranch, List<String> tags,
                      String agentNodeId, String kind, String ownerId) {
}
