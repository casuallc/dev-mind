package com.devmind.test.dto;

import java.util.List;

/**
 * 创建并执行测试运行。suiteIds 为选中的套件（必须非空）；目标 baseUrl 优先级：
 * baseUrl（显式）→ serverId（http 服务器取其 baseUrl）→ deploymentId 关联部署的服务器。
 */
public record CreateTestRunRequest(
        String projectId,
        List<Long> suiteIds,
        Long deploymentId,
        Long serverId,
        String baseUrl) {
}
