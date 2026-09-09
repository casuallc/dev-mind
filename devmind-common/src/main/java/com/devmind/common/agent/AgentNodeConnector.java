package com.devmind.common.agent;

import java.util.List;

/**
 * CAP-21 节点连接 SPI（会话模块 → agent 模块：经节点 WS 长连接下发指令）。
 * 接口定义在 common（同 PlatformIntegrationHook 先例），实现方 devmind-agent 由调用方
 * 以 {@code ObjectProvider<AgentNodeConnector>} 探测注入——未装配 agent 模块时远程会话不可用。
 * 所有方法同步返回；节点离线/发送失败抛 DevMindException(CONFLICT)，由调用方转成用户可读错误。
 */
public interface AgentNodeConnector {

    /** 节点当前是否有活跃连接（ONLINE）。 */
    boolean isOnline(String nodeId);

    /**
     * 平台默认执行节点 id（agent_nodes.is_default；无默认返回 null）。
     * 会话调度链（CAP-34）：显式指定 > 项目默认 > 平台默认；皆无命中 409，无本机回落。
     */
    String defaultNodeId();

    /**
     * 下发 launch 并阻塞等 runner ack（超时/失败/离线抛 DevMindException）。
     * 成功返回表示 runner 侧子进程已拉起。
     */
    void launch(String nodeId, AgentLaunchCommand cmd);

    /** 注入用户输入（纯文本，协议包装在 runner 侧完成）。 */
    void sendInput(String nodeId, String sessionId, String text);

    /**
     * CAP-32：注入用户输入（可带图片附件 base64，随 input 帧 images 字段下发）。
     * 默认降级为纯文本（丢图）——实现方应覆盖本方法；旧 runner 忽略 images 字段优雅降级。
     */
    default void sendInput(String nodeId, String sessionId, String text, List<InputImage> images) {
        sendInput(nodeId, sessionId, text);
    }

    /** 授权响应。 */
    void sendAuthorize(String nodeId, String sessionId, String requestId, boolean accepted, String scope);

    /** 优雅结束（runner 关 stdin，agent 自然退出后回 exit 帧）。 */
    void sendFinish(String nodeId, String sessionId);

    /** 强杀。 */
    void sendKill(String nodeId, String sessionId);

    /** 挂起（杀进程，会话记录保留可 resume）。 */
    void sendSuspend(String nodeId, String sessionId);

    /**
     * CAP-34 FR-08 协议版本门控：节点当前连接的 runner 是否支持 {@code minVersion}
     * （见 {@link AgentProtocol}）。无 hello 版本记录的老 runner 按 v1 对待。
     * 默认 false（未装配 agent 模块 = 无任何节点可用）；下发「必须认识」的新帧前先查。
     */
    default boolean supports(String nodeId, int minVersion) {
        return false;
    }

    /**
     * CAP-34 FR-07 标签调度：节点标签是否覆盖全部 required（required 为空 = 恒匹配）。
     * 默认 true（未装配 agent 模块时退化为不门控，维持旧行为）。
     */
    default boolean nodeMatches(String nodeId, List<String> requiredLabels) {
        return true;
    }

    /**
     * CAP-34 FR-07 标签调度：在在线节点中挑一个标签覆盖全部 required 的（无命中返回 null）。
     * 默认 null（未装配 agent 模块 = 无节点可挑）。
     */
    default String pickNodeByLabels(List<String> requiredLabels) {
        return null;
    }

    /**
     * CAP-36：下发 exec 帧并阻塞至 exec_exit 收口（exec_log 帧实时推 sink，stderr 行前缀
     * 「[stderr] 」——与 LocalStepRunner 日志形态一致）。节点离线/协议版本不足（需 v3+）/
     * 等待超时抛 DevMindException(CONFLICT)；进程非零退出不抛，看 {@link AgentExecResult#exitCode()}。
     * 默认实现 = agent 模块未装配（无任何执行节点可用）。
     */
    default AgentExecResult exec(String nodeId, AgentExecCommand cmd, java.util.function.Consumer<String> sink) {
        throw new com.devmind.common.exception.DevMindException(
                com.devmind.common.exception.ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
    }
}
