package com.devmind.test.dto;

import java.util.List;

/**
 * 创建并执行测试运行。suiteIds 为选中的套件（必须非空）；目标 baseUrl 优先级：
 * baseUrl（显式）→ environmentId（环境变量 baseUrl/BASE_URL + 首个节点）→ deploymentId 关联部署的环境变量。
 * agentNodeId 为 health command 用例的执行节点（CAP-36；缺省取环境首节点，再缺省走节点路由链）。
 * workItemId 可选（P0-6 关联约定，须属于该项目）。
 */
public record CreateTestRunRequest(
        String projectId,
        String workItemId,
        List<Long> suiteIds,
        Long deploymentId,
        /** health command 用例目标 runner 节点 id（可空） */
        String agentNodeId,
        /** 目标环境 id（P1-1）：提供默认节点与变量（baseUrl/BASE_URL 作为默认测试目标） */
        Long environmentId,
        String baseUrl) {
}
