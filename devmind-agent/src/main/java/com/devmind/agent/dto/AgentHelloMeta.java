package com.devmind.agent.dto;

/**
 * CAP-34 runner hello 帧元数据（全部可空 = 旧 runner 未上报的字段，落库时不动旧值）：
 * os/capabilities/runnerVersion 自 CAP-21 有；workspaceBytes（FR-05）、protocolVersion（FR-08）、
 * labels/toolchainJson（FR-07，labels 为 runner 配置标签 CSV，空 = 未配置不覆盖服务端编辑值）。
 */
public record AgentHelloMeta(String os, String capabilities, String runnerVersion,
                             Long workspaceBytes, Integer protocolVersion,
                             String labels, String toolchainJson) {
}
