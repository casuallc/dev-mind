package com.devmind.test.dto;

import java.util.List;

/**
 * CAP-69 脚本套件写请求（POST/PUT 整体替换语义；env 中 secret 条目的值为掩码时保留原值）。
 * projectId 必填：脚本套件强制绑定项目（入口 = 项目「测试」页），run 进项目运行历史。
 */
public record ScriptSuiteRequest(
        String projectId,
        String name,
        String repoUrl,
        String branch,
        String workSubdir,
        String command,
        String junitPath,
        List<ScriptSuiteEnv> env,
        String agentNodeId,
        Integer timeoutSec,
        String workspaceKey) {
}
