package com.devmind.common.agent.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 会话执行器 SPI：把会话拉起成一个子进程。两个内置实现：
 * {@link FakeProcessLauncher}（内置假进程，自测/无 claude 环境）
 * 与 {@link CliProcessLauncher}（真实 Claude Code）。
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

    /**
     * 启动上下文（CLI 相关细节集中在各实现内）。
     *
     * @param env             CAP-24：附加进程环境变量（GIT_AUTHOR_NAME 等提交身份变量）；
     *                        null/空 = 不附加。随进程隔离，不写 git config（worktree 共享主仓配置，并行会话会互踩）
     * @param resumeSessionId 续接目标 CLI 会话 id（claude --resume）；null/空 = 全新对话。
     *                        会话历史存于 CLI 配置目录（按 cwd 归档），进程退出不丢失；
     *                        文件已清理时 CLI 报错退出，由 launch 失败上抛，不降级为新对话
     */
    record LaunchContext(String sessionId, Path worktree, String taskSpec,
                         String model, String permissionMode, Map<String, String> env,
                         String resumeSessionId) {

        /** 兼容构造器：无续接（全新对话）。 */
        public LaunchContext(String sessionId, Path worktree, String taskSpec,
                             String model, String permissionMode, Map<String, String> env) {
            this(sessionId, worktree, taskSpec, model, permissionMode, env, null);
        }
    }
}
