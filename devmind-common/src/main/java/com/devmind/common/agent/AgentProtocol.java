package com.devmind.common.agent;

/**
 * CAP-34 FR-08 runner ↔ 服务端 WS 协议版本常量。hello 帧携带 {@code protocolVersion}，
 * 服务端据此门控下发（老 runner 不认识新帧类型时静默忽略，故门控只用于「必须认识」的帧）。
 *
 * <p>版本史：v1 = CAP-21~FR-03 基线（launch/input/authorize/finish/kill/suspend/hello/heartbeat/
 * event/exit/launched/upgrade/upgrade_ack）；v2 = FR-04~08（对账/GC/版本协商/工具链标签，
 * 均为 hello 可选字段，无新下行帧）；v3 = CAP-36 exec 帧（exec/exec_log/exec_exit，
 * 构建/测试/部署/发版下发 runner 执行）；v4 = CAP-39 collect_output 帧
 * （collect_output/output_collected，进行中会话产出按需即时回传）；
 * v5 = CAP-41 launch 帧 kind:"worklog"（runner 持久工作区 {user.home}/worklog/&lt;owner&gt;，
 * 老 runner 不认识会落入 legacy 兜底目录跑偏，故属「必须认识」需门控）；
 * v6 = CAP-41 M3 worklog_push 帧（worklog_push/worklog_push_ack，工作日志持久工作区
 * 手动 push 到用户绑定的远端仓库）。v7 = CAP-42 每用户固定工作区（launch 帧
 * workspaceOwner——repo 会话工作区固定到 &lt;projectId&gt;/&lt;owner&gt;/{main,work}，
 * 老 runner 会忽略并落入 sessions/&lt;sid&gt; 旧布局，故属「必须认识」需门控；
 * 以及 workspace_finalize/workspace_finalize_ack 帧——手动收口：合并会话分支到基线
 * + push + 删 worktree）。v8 = CAP-43 节点外网代理（launch/worklog_push/exec/
 * workspace_finalize 帧携带 proxy{url,scopes}——老 runner 会忽略字段导致该走代理的
 * 网络操作直连失败，故属「必须认识」需门控）。</p>
 */
public final class AgentProtocol {

    /** 当前 runner 协议版本 */
    public static final int CURRENT = 8;

    /** CAP-36 exec 帧（构建/部署/测试/发版下发 runner）所需最低版本 */
    public static final int EXEC_FRAMES = 3;

    /** CAP-39 collect_output 帧（进行中会话产出按需回传）所需最低版本 */
    public static final int COLLECT_OUTPUT_FRAMES = 4;

    /** CAP-41 launch kind:"worklog"（runner 持久工作区）所需最低版本 */
    public static final int WORKLOG_KIND = 5;

    /** CAP-41 M3 worklog_push 帧（工作日志持久工作区手动 push 远端）所需最低版本 */
    public static final int WORKLOG_PUSH_FRAMES = 6;

    /** CAP-42 每用户固定工作区（launch workspaceOwner）与 workspace_finalize 帧所需最低版本 */
    public static final int PER_USER_WORKSPACE = 7;

    /** CAP-43 节点外网代理（下行帧携带 proxy 对象）所需最低版本 */
    public static final int NODE_PROXY = 8;

    /** hello 未携带 protocolVersion 的老 runner 按此版本对待 */
    public static final int DEFAULT_WHEN_ABSENT = 1;

    private AgentProtocol() {
    }
}
