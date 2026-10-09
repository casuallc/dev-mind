package com.devmind.test.dto;

import java.util.List;

/**
 * CAP-69 脚本套件写请求（POST/PUT 整体替换语义；env 中 secret 条目的值为掩码时保留原值）。
 */
public record ScriptSuiteRequest(
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
