package com.devmind.test.dto;

import java.util.Map;

/** CAP-104 单用例运行：环境/节点/baseUrl 覆盖与参数注入。 */
public record CaseRunRequest(
        String projectId,
        String workItemId,
        Long environmentId,
        String agentNodeId,
        String baseUrl,
        Map<String, String> parameters) {
}
