package com.devmind.test.executor;

import com.devmind.project.model.EnvironmentEntity;

import java.util.Map;

/** 单用例执行上下文：运行、环境、目标与参数覆盖。 */
public record CaseExecInput(
        String projectId,
        Long runId,
        EnvironmentEntity environment,
        String explicitNodeId,
        String baseUrl,
        String suiteCommit,
        Map<String, String> parameters,
        Long caseId,
        String caseKey) {

    public CaseExecInput {
        parameters = parameters == null ? Map.of() : parameters;
    }

    /** 非程序型调用（HTTP/MANUAL）不需要用例 id 的旧签名。 */
    public CaseExecInput(String projectId, Long runId, EnvironmentEntity environment, String explicitNodeId,
                         String baseUrl, String suiteCommit, Map<String, String> parameters) {
        this(projectId, runId, environment, explicitNodeId, baseUrl, suiteCommit, parameters, null, null);
    }
}
