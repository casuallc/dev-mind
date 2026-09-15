package com.devmind.common.agent;

/**
 * CAP-42 每用户固定工作区手动收口 ack（workspace_finalize 帧的应答）：runner 对
 * {@code <workspaceRoot>/<projectId>/<owner>/} 下的固定 worktree 逐库执行
 * 「合并会话分支到基线 → push 基线 + best-effort push 会话分支 → 删 worktree」后回此结果。
 *
 * @param ok     全部库收口成功
 * @param detail 成功摘要（各库合并提交/推送结果，已经 runner 侧 sanitize 脱敏；ok=false 时为 null）
 * @param error  失败原因（已经 sanitize 脱敏；冲突/脏工作区时目录保留可重试；ok=true 时为 null）
 */
public record FinalizeResult(boolean ok, String detail, String error) {

    public static FinalizeResult ok(String detail) {
        return new FinalizeResult(true, detail, null);
    }

    public static FinalizeResult failed(String error) {
        return new FinalizeResult(false, null, error);
    }
}
