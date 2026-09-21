package com.devmind.common.agent;

/**
 * CAP-42 固定工作区释放 ack（workspace_release 帧的应答）：删除会话时 runner 对
 * {@code <workspaceRoot>/<projectId>/<owner>/} 下的固定 worktree 逐库执行
 * 「丢弃未提交改动 → 删 worktree → 删本地会话分支」后回此结果。
 *
 * <p>与 {@link FinalizeResult}（收口：合并+push+删）的区别是<b>不合并不 push</b>——
 * 删除会话的语义就是丢弃，不能把会话分支合入基线，也不该在删除时改动远端。</p>
 *
 * @param ok     全部库释放成功
 * @param detail 成功摘要（各库丢弃的脏文件数/未合并提交数，已经 runner 侧 sanitize 脱敏；
 *               ok=false 时为 null）
 * @param error  失败原因（已经 sanitize 脱敏；目录保留可重试；ok=true 时为 null）
 */
public record WorkspaceReleaseResult(boolean ok, String detail, String error) {

    public static WorkspaceReleaseResult ok(String detail) {
        return new WorkspaceReleaseResult(true, detail, null);
    }

    public static WorkspaceReleaseResult failed(String error) {
        return new WorkspaceReleaseResult(false, null, error);
    }
}
