package com.devmind.test.dto;

import java.util.List;

/**
 * 创建并执行测试运行。suiteIds 为选中的套件（必须非空）；目标 baseUrl 优先级：
 * baseUrl（显式）→ serverId（http 服务器取其 baseUrl）→ environmentId（环境变量 baseUrl/BASE_URL + 首台服务器）
 * → deploymentId 关联部署的服务器。
 * requirementId 可选（P0-6 关联约定，须属于该项目）。
 */
public record CreateTestRunRequest(
        String projectId,
        String requirementId,
        List<Long> suiteIds,
        Long deploymentId,
        Long serverId,
        /** 目标环境 id（P1-1）：提供默认服务器与变量（baseUrl/BASE_URL 作为默认测试目标） */
        Long environmentId,
        String baseUrl) {
}
