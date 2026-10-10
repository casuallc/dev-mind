package com.devmind.common.agent;

/**
 * CAP-73 verify_ack 收口结果（三类 verify_* 指令共用，协议 v22 起）。
 *
 * @param ok           指令整体成败（失败原因在 error；进程非零退出/超时/拉包失败都折算在这里）
 * @param error        失败原因（ok=true 时为 null）
 * @param fileCount    verify_pack 专属：产物树文件数（其余指令 null）
 * @param filesChanged verify_patch 专属：本次补丁实际变更文件数（覆盖+新增+删除；其余指令 null）
 */
public record AgentVerifyResult(boolean ok, String error, Integer fileCount, Integer filesChanged) {

    /** 工厂名避开记录访问器 ok() 冲突：success()/packed()/patched()/failed() */
    public static AgentVerifyResult success() {
        return new AgentVerifyResult(true, null, null, null);
    }

    public static AgentVerifyResult packed(int fileCount) {
        return new AgentVerifyResult(true, null, fileCount, null);
    }

    public static AgentVerifyResult patched(int filesChanged) {
        return new AgentVerifyResult(true, null, null, filesChanged);
    }

    public static AgentVerifyResult failed(String error) {
        return new AgentVerifyResult(false, error, null, null);
    }
}
