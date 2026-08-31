package com.devmind.session.model;

/**
 * 会话状态机。
 * <pre>
 * create ─▶ RUNNING ── permission_request ─▶ WAITING_AUTH ── 授权/拒绝 ─▶ RUNNING
 *             │            （启发式提问 ─▶ WAITING_INPUT，同样可回复继续）
 *             ├─ 正常退出 ─▶ DONE
 *             ├─ 异常退出 ─▶ FAILED
 *             ├─ 挂起     ─▶ SUSPENDED（杀进程保留 worktree，可 resume 续跑）
 *             └─ kill     ─▶ TERMINATED
 * </pre>
 */
public enum SessionState {
    RUNNING,
    WAITING_INPUT,
    WAITING_AUTH,
    DONE,
    FAILED,
    SUSPENDED,
    TERMINATED;

    /** 会话是否仍在占用进程（限制并发时计数用） */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_INPUT || this == WAITING_AUTH;
    }
}
