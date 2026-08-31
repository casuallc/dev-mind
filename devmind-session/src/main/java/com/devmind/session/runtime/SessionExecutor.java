package com.devmind.session.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 会话执行器 SPI：把会话拉起成一个子进程。MVP 两个实现：
 * {@link FakeProcessLauncher}（内置假进程，自测/无 claude 环境）
 * 与 {@link CliProcessLauncher}（真实 Claude Code）。
 * 后续扩展远程执行时在此新增实现即可。
 */
public interface SessionExecutor {

    String name();

    /**
     * 启动子进程。
     *
     * @param ctx 启动上下文
     * @return 已启动的进程（stdout/stderr 均被重定向到管道，需持续读取防死锁）
     */
    Process launch(LaunchContext ctx) throws IOException;

    /** 启动上下文（CLI 相关细节集中在各实现内）。 */
    record LaunchContext(String sessionId, Path worktree, String taskSpec,
                         String model, String permissionMode) {}
}
