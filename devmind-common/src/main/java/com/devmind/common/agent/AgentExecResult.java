package com.devmind.common.agent;

/**
 * CAP-36 exec 收口结果（exec_exit 帧映射）。
 *
 * @param exitCode 进程退出码（-1 = 未拉起/异常）
 * @param timedOut 超时整树 kill
 * @param error    拉起阶段错误（白名单拒绝/工作区准备失败/传输异常等）；进程非零退出不算 error
 */
public record AgentExecResult(int exitCode, boolean timedOut, String error) {

    public boolean ok() {
        return error == null && !timedOut && exitCode == 0;
    }

    public static AgentExecResult failed(String error) {
        return new AgentExecResult(-1, false, error);
    }
}
