package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-69 脚本套件视图。env 中 secret=true 的值恒为掩码 {@link ScriptSuiteEnv#MASK}（编辑回显用，
 * PUT 时掩码原样回传=该条值不变）。
 */
public record ScriptSuiteView(
        Long id,
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
        String workspaceKey,
        Instant createdAt) {
}
