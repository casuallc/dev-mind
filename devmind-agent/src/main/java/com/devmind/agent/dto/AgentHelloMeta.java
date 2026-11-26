package com.devmind.agent.dto;

/**
 * CAP-34 runner hello 帧元数据（全部可空 = 旧 runner 未上报的字段，落库时不动旧值）：
 * os/capabilities/runnerVersion 自 CAP-21 有；workspaceBytes（FR-05）、protocolVersion（FR-08）、
 * labels/toolchainJson（FR-07，labels 为 runner 配置标签 CSV，空 = 未配置不覆盖服务端编辑值）。
 * runUser/serviceMode/serviceName/workDir = runner 运行身份（runUser 为节点侧 whoami；
 * serviceMode ∈ windows-service / systemd / process），供节点页展示与「修改执行用户」引导。
 */
public record AgentHelloMeta(String os, String capabilities, String runnerVersion,
                             Long workspaceBytes, Integer protocolVersion,
                             String labels, String toolchainJson,
                             String runUser, String serviceMode, String serviceName, String workDir) {

    /** 兼容构造（运行身份上报前的 7 参签名）：运行身份字段全 null（= 未上报，不动旧值）。 */
    public AgentHelloMeta(String os, String capabilities, String runnerVersion,
                          Long workspaceBytes, Integer protocolVersion,
                          String labels, String toolchainJson) {
        this(os, capabilities, runnerVersion, workspaceBytes, protocolVersion, labels, toolchainJson,
                null, null, null, null);
    }
}
