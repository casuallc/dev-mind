package com.devmind.common.agent;

/**
 * CAP-57 proc_ack 上行帧模型（runner → 服务端：进程管控结果）。
 *
 * @param ok     动作本身是否执行成功（start 拉起 / stop 杀净 / status 探测完成）；
 *               false 时 error 带人读原因（越界收容拒绝 / 启动即退出 / pid 不存在等）
 * @param action 回显请求动作
 * @param status 动作后的进程状态：{@link #RUNNING} / {@link #STOPPED} / {@link #UNKNOWN}
 * @param pid    进程 pid（RUNNING 时非空）
 * @param error  失败原因（ok=true 时空串）
 * @param detail 附带信息（start 时的命令行、status 时的命令行摘要，可空）
 */
public record AgentProcResult(boolean ok, String action, String status, Long pid,
                              String error, String detail) {

    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";
    public static final String UNKNOWN = "UNKNOWN";

    public static AgentProcResult ok(String action, String status, Long pid, String detail) {
        return new AgentProcResult(true, action, status, pid, "", detail == null ? "" : detail);
    }

    public static AgentProcResult fail(String action, String status, String error) {
        return new AgentProcResult(false, action, status, null,
                error == null ? "未知错误" : error, "");
    }
}
