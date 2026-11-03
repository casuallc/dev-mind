package com.devmind.execution.runner;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.program.ProgramManifest;
import com.devmind.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-36 节点调度（执行器共用）：executor=AGENT 的路由链——显式指定 > 项目默认节点 >
 * 平台默认节点 > 标签匹配兜底 > 皆无命中 409（对齐会话路由 CAP-34 FR-02/07 语义，无本机回落）。
 */
@Component
public class AgentNodeRouter {

    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    public AgentNodeRouter(ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.connectorProvider = connectorProvider;
    }

    /**
     * @param explicitNodeId       本次触发显式指定的节点（可空）
     * @param projectDefaultNodeId 项目默认节点（Project.agentNodeId，可空）
     * @param requiredLabels       标签要求（空 = 不门控）
     * @return 命中的在线节点 id
     */
    public String route(String explicitNodeId, String projectDefaultNodeId, List<String> requiredLabels) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        List<String> labels = requiredLabels == null ? List.of() : requiredLabels;
        if (explicitNodeId != null && !explicitNodeId.isBlank()) {
            String id = explicitNodeId.strip();
            if (!connector.nodeMatches(id, labels)) {
                throw new DevMindException(ErrorCode.CONFLICT, "指定节点 " + id + " 不满足标签要求: " + labels);
            }
            if (!connector.isOnline(id)) {
                throw new DevMindException(ErrorCode.CONFLICT, "指定节点不在线: " + id);
            }
            return id;
        }
        for (String candidate : new String[]{projectDefaultNodeId, connector.defaultNodeId()}) {
            if (candidate != null && !candidate.isBlank()
                    && connector.isOnline(candidate) && connector.nodeMatches(candidate, labels)) {
                return candidate;
            }
        }
        if (!labels.isEmpty()) {
            String picked = connector.pickNodeByLabels(labels);
            if (picked != null) {
                return picked;
            }
        }
        throw new DevMindException(ErrorCode.CONFLICT, labels.isEmpty()
                ? "无可用执行节点（无在线节点；请到 Agent 节点页启动 runner 或设置平台默认节点）"
                : "无满足标签的在线节点: " + labels);
    }

    /** 节点在线且协议支持 exec 帧（v3+）；不满足抛 409 带可操作建议。 */
    public void requireExecCapable(String nodeId) {
        requireProtocol(nodeId, AgentProtocol.EXEC_FRAMES, "exec");
    }

    /**
     * CAP-56：节点在线且协议支持 exec 帧携带执行包（v14+）——决策实验室的评测/微调需要它。
     *
     * <p>在<b>触发阶段</b>查而不是等 exec 时再炸：运行是在后台线程里跑的，那时拒绝只会留下一条
     * FAILED 记录和一串"协议版本过低"的日志，而人是在页面上按的按钮——按钮那一刻就该知道
     * 节点不够新。判据与 {@code AgentConnectionRegistry.exec} 内的门控同源（同一个版本常量）。</p>
     */
    public void requireBundleCapable(String nodeId) {
        requireProtocol(nodeId, AgentProtocol.EXEC_BUNDLE, "决策实验室评测/微调（执行包由 runner 拉取物化）");
    }

    /**
     * CAP-104 M1.5：节点在线且支持程序包树形解包（v24+）；随后按清单预检
     * OS/架构/运行时版本——不匹配在执行前 BLOCKED，而不是让程序跑到一半报「命令不存在」。
     */
    public void requireProgramCompatible(String nodeId, ProgramManifest manifest) {
        requireProgramCompatible(nodeId, manifest, null);
    }

    /**
     * CAP-106 FR-06：带本次执行合并 env 的预检——toolchain 快照缺所需 key 时，检查
     * {@code execEnv}（调用方合并链：环境 variables &lt; 用例 env）叠上节点 env 后是否含
     * {@code PATH}（或该 key 对应的 {@code *_HOME}，java→JAVA_HOME）覆盖：无覆盖 → 维持
     * BLOCKED；有覆盖 → 放行，最终判定下沉 runner 执行期探测（仍不满足也跑不了入口脚本）。
     */
    public void requireProgramCompatible(String nodeId, ProgramManifest manifest,
                                         java.util.Map<String, String> execEnv) {
        requireProtocol(nodeId, AgentProtocol.TEST_PROGRAM_BUNDLE, "程序型用例（程序包由 runner 树形解包）");
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        String os = connector.nodeOs(nodeId);
        if (!manifest.matchesOs(os)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 OS（" + (os == null ? "未上报" : os)
                            + "）与程序包 targetOs=" + manifest.targetOs() + " 不匹配");
        }
        String arch = ProgramManifest.archOfNodeOs(os);
        if (!manifest.matchesArch(arch)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的架构（" + (arch == null ? "未上报" : arch)
                            + "）与程序包 targetArch=" + manifest.targetArch() + " 不匹配");
        }
        String key = manifest.requiredToolchainKey();
        if (key == null) {
            return;
        }
        String actual = connector.nodeToolchain(nodeId).get(key);
        if (actual == null || actual.isBlank()) {
            if (hasRuntimeEnvOverride(connector.nodeEnv(nodeId), execEnv, key)) {
                return; // FR-06：注入 env 显式覆盖 PATH/JAVA_HOME → 判定下沉 runner 执行期探测
            }
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 未探测到 " + key + " 运行时，无法满足程序包 runtime="
                            + manifest.runtime() + "（若刚在节点上安装运行时，等一个探测周期（默认 10 分钟）"
                            + "或重启 runner 后重试；也可在节点页配 PATH/JAVA_HOME 环境变量）");
        }
        if (!manifest.satisfiesRuntimeVersion(actual)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 " + key + " 版本 " + actual
                            + " 不满足程序包 runtimeVersion=" + manifest.runtimeVersion());
        }
    }

    /** FR-06：合并 env（节点 env < execEnv）是否含 PATH / key 对应 *_HOME 的运行时覆盖。 */
    private static boolean hasRuntimeEnvOverride(java.util.Map<String, String> nodeEnv,
                                                 java.util.Map<String, String> execEnv, String key) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (nodeEnv != null) {
            merged.putAll(nodeEnv);
        }
        if (execEnv != null) {
            merged.putAll(execEnv);
        }
        for (java.util.Map.Entry<String, String> e : merged.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if ("PATH".equalsIgnoreCase(e.getKey())) {
                return true;
            }
            if ("java".equals(key) && "JAVA_HOME".equals(e.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * CAP-57：节点在线且协议支持 proc 帧（v15+）——分类服务实例的起停/状态管控需要它。
     * 在操作触发阶段查：人是在管控台按的「启动」，那一刻就该知道节点不够新，而不是等
     * 后台报一串"协议版本过低"。
     */
    public void requireProcCapable(String nodeId) {
        requireProtocol(nodeId, AgentProtocol.PROC_FRAMES, "分类服务进程管控");
    }

    /** CAP-57：节点在线且协议支持 pkg 帧（v15+）——安装包分发到节点需要它。 */
    public void requirePkgCapable(String nodeId) {
        requireProtocol(nodeId, AgentProtocol.PKG_FRAMES, "分类服务安装包分发");
    }

    /** 在线 + 协议版本双关：两处判据只写一份，离线/过低的报错才不会各说各话 */
    private void requireProtocol(String nodeId, int minVersion, String what) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        if (!connector.isOnline(nodeId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + nodeId);
        }
        if (!connector.supports(nodeId, minVersion)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（" + what + " 需 v" + minVersion
                            + "+），请到节点页升级 runner");
        }
    }
}
