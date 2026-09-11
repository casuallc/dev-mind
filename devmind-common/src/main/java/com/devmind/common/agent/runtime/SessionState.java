package com.devmind.common.agent.runtime;

/**
 * 会话状态机（CAP-30 起由 session 模块上移，session/chat 共用）。
 * <pre>
 * create ─▶ RUNNING ── permission_request ─▶ WAITING_AUTH ── 授权/拒绝 ─▶ RUNNING
 *             │            （启发式提问 ─▶ WAITING_INPUT，同样可回复继续）
 *             ├─ 正常退出 ─▶ DONE
 *             ├─ 异常退出 ─▶ FAILED
 *             ├─ 挂起     ─▶ SUSPENDED（杀进程保留工作区，可 resume 续跑）
 *             └─ kill     ─▶ TERMINATED
 *
 * resume：SUSPENDED/DONE/FAILED/TERMINATED ─▶ RUNNING（重新拉起进程，claude --resume
 * 续接对话历史；历史在 runner 侧 CLI 配置目录按 cwd 归档，已清理则恢复失败）
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
