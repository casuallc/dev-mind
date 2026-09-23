package com.devmind.common.agent;

/**
 * CAP-58 terminal_exec 应答：runner 对单条终端命令执行的回执。
 *
 * @param ok       命令是否被执行且正常收口（白名单拒绝/路径越界/会话不在本节点等 → false + error）；
 *                 注意命令本身非零退出仍是 ok=true，看 {@link #exitCode()}
 * @param exitCode 进程退出码（ok=false 时为 -1）
 * @param stdout   标准输出（超 128KB 保留尾部）
 * @param stderr   标准错误（同上）
 * @param cwd      命令执行后的新 cwd（相对代码目录的 POSIX 路径；越界 cd 被拒时维持原值）
 * @param timedOut 是否因超时被整树杀
 * @param error    失败原因（用户可读）
 */
public record TerminalExecResult(boolean ok, int exitCode, String stdout, String stderr,
                                 String cwd, boolean timedOut, String error) {

    public static TerminalExecResult of(int exitCode, String stdout, String stderr, String cwd,
                                        boolean timedOut) {
        return new TerminalExecResult(true, exitCode, stdout, stderr, cwd, timedOut, null);
    }

    public static TerminalExecResult failed(String error) {
        return new TerminalExecResult(false, -1, "", "", null, false, error);
    }
}
