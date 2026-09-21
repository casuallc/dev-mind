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

    /**
     * CAP-39：下发 collect_output 帧并阻塞等 output_collected ack——runner 对在本节点运行中的
     * 会话即时扫描 `.devmind/output/` 上传（与退出时自动回传同通道），ack 到达时产出已落库。
     * 节点离线/协议版本不足（需 v4+）/等待超时抛 DevMindException(CONFLICT)；
     * 会话不在本节点运行不抛，看 {@link AgentCollectResult#ok()}（退出时已自动回传）。
     * 默认实现 = agent 模块未装配（无任何执行节点可用）。
     */
    default AgentCollectResult collectOutput(String nodeId, String sessionId) {
        throw new com.devmind.common.exception.DevMindException(
                com.devmind.common.exception.ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
    }

    /**
     * CAP-41 M3：下发 worklog_push 帧并阻塞等 worklog_push_ack——runner 对持久工作区
     * `{worklogRoot}/<worklogOwner>/` 执行 git push（remote 幂等绑定 origin=cleanUrl，
     * push HEAD:&lt;branch&gt;，token 仅随帧传输严禁进日志）。
     * 节点离线/协议版本不足（需 v6+）/等待超时抛 DevMindException(CONFLICT)；
     * push 非零退出（坏 URL/鉴权失败/非快进）不抛，看 {@link WorklogPushResult#ok()}。
     * 默认实现 = agent 模块未装配（无任何执行节点可用）。
     */
    default WorklogPushResult pushWorklog(String nodeId, String worklogOwner, String remoteUrl,
                                          String branch, String token) {
        throw new com.devmind.common.exception.DevMindException(
                com.devmind.common.exception.ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
    }

    /**
     * CAP-42：下发 workspace_finalize 帧并阻塞等 workspace_finalize_ack——runner 对固定工作区
     * {@code <workspaceRoot>/<projectId>/<workspaceOwner>/} 逐库执行手动收口（合并会话分支到基线
     * → push 基线 + best-effort push 会话分支 → 删 worktree；discardChanges=true 先 reset --hard
     * + clean -fd 清未提交脏文件）。specs 带各库 remoteUrl/baseBranch/会话 branch/name/token
     * （token 仅随帧传输严禁进日志）。
     * 节点离线/协议版本不足（需 v7+）/等待超时抛 DevMindException(CONFLICT)；
     * 合并冲突/脏工作区/push 失败不抛，看 {@link FinalizeResult#ok()}（目录保留可重试）。
     * 默认实现 = agent 模块未装配（无任何执行节点可用）。
     */
    default FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                             String workspaceOwner,
                                             List<AgentLaunchCommand.RepoSpec> specs,
                                             boolean discardChanges) {
        return finalizeWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs, discardChanges, null);
    }

    /**
     * CAP-51 需求粒度收口：语义同上，但工作区定位到
     * {@code <workspaceRoot>/<projectId>/<workspaceOwner>/worktrees/<workspaceKey>}
     * （收口后<b>保留</b>工作树，见 {@link RunnerWorkspace#finalize} 的 CAP-51 语义）。
     *
     * <p>workspaceKey 为空 = 存量会话（旧布局 {@code work/}），协议门控退回 v7；
     * 非空时需 v10+（老 runner 忽略该字段会去收口旧布局，与需求工作区不符）。</p>
     */
    default FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                             String workspaceOwner,
                                             List<AgentLaunchCommand.RepoSpec> specs,
                                             boolean discardChanges, String workspaceKey) {
        throw new com.devmind.common.exception.DevMindException(
                com.devmind.common.exception.ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
    }

    /**
     * CAP-42：下发 workspace_release 帧并阻塞等 workspace_release_ack——删除会话时释放固定工作区
     * {@code <workspaceRoot>/<projectId>/<workspaceOwner>/}：逐库丢弃未提交改动 → 删 worktree
     * → 删本地会话分支（<b>不合并不 push</b>，与 {@link #finalizeWorkspace} 的区别见
     * {@link WorkspaceReleaseResult}）。
     *
     * <p>节点离线/协议版本不足（需 v9+）/等待超时抛 DevMindException(CONFLICT)——删除链路必须
     * fail-visible：静默跳过释放会把固定目录留成孤儿，该 (项目,用户) 之后永远开不了新会话。</p>
     *
     * 默认实现 = agent 模块未装配（无任何执行节点可用）。
     */
    default WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                    String workspaceOwner,
                                                    List<AgentLaunchCommand.RepoSpec> specs) {
        return releaseWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs, null);
    }

    /**
     * CAP-51 需求粒度释放：工作区定位到
     * {@code <workspaceRoot>/<projectId>/<workspaceOwner>/worktrees/<workspaceKey>}，
     * 逐库「丢弃未提交改动 → 删 worktree → 删本地需求分支」（不合并不 push）。
     * 需求删除/需求终态清理走本方法（一个需求一条记录，释放即整块回收）。
     *
     * <p>workspaceKey 为空 = 存量会话（旧布局 {@code work/}），协议门控退回 v9；
     * 非空时需 v10+。</p>
     */
    default WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                    String workspaceOwner,
                                                    List<AgentLaunchCommand.RepoSpec> specs,
                                                    String workspaceKey) {
        throw new com.devmind.common.exception.DevMindException(
                com.devmind.common.exception.ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
    }
}
