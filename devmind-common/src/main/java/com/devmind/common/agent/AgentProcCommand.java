package com.devmind.common.agent;

import java.util.List;
import java.util.Map;

/**
 * CAP-57 proc 帧模型（服务实例进程管控下发 runner，协议 v15 起）。
 *
 * <p>与 exec 帧的分工：exec 是<b>一次性</b>脚本（超时杀树、跑完即弃），proc 管的是<b>比 runner
 * 活得久</b>的长驻进程（边车/服务）——runner 只负责拉起与树杀，不守护不自动重启
 * （v1 无 supervisor，实例崩溃由服务端健康轮询标红）。</p>
 *
 * <p><b>argv 为王</b>：runner 直接 {@code ProcessBuilder(argv)}，<b>不过 shell</b>——
 * Windows 节点没有 bash/nohup，shell 转义整类问题从源头消掉；{@code command} 只是
 * 给人看的展示串（实例详情页/日志）。路径（workdir/pidFile/logFile）一律
 * <b>相对 {@code <workspaceRoot>/classify/} 根</b>，runner 拼 workspaceRoot 并做收容校验
 * （归一化后越界即 ack 拒绝）——服务端不感知节点路径，天然跨平台。</p>
 *
 * <p>安全论证：server→runner 通道本就承载 upgrade 帧（服务端能推任意 jar 让 runner 执行），
 * proc 帧信任等级不高于它；命令非自由表单，由服务端按实例实体（ADMIN 限定管理）组帧，
 * runner 侧再做目录收容，不执行帧里收容根之外的任何东西。</p>
 *
 * @param requestId  本次请求唯一 id（ack 路由键，[a-zA-Z0-9._-]）
 * @param action     start / stop / restart / status
 * @param instanceId 服务实例 id（proc.json/pidfile 归目录属：{@code run/inst-<instanceId>/}）
 * @param argv       进程参数数组（start/restart 必填；stop/status 可空）
 * @param command    展示用命令串（仅日志/界面，runner 不执行它）
 * @param env        注入子进程的环境变量（可空；服务端已展开 ${PKG_DIR:<id>} 占位符）
 * @param workdir    工作目录（相对 classify/ 根，如 {@code packages/pkg-3}）
 * @param pidFile    pid 文件（相对 classify/ 根，如 {@code run/inst-5/sidecar.pid}）
 * @param logFile     stdout/stderr 追加写入的日志文件（相对 classify/ 根）
 */
public record AgentProcCommand(String requestId, String action, String instanceId,
                               List<String> argv, String command, Map<String, String> env,
                               String workdir, String pidFile, String logFile) {

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_RESTART = "restart";
    public static final String ACTION_STATUS = "status";
}
