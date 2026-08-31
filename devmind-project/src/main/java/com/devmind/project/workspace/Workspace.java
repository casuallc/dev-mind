package com.devmind.project.workspace;

import java.nio.file.Path;

/**
 * 工作区（P1-3 Workspace 抽象）：一次会话/任务的隔离工作区。
 * Session 只面向本接口，不再直接捏 worktree；远程/容器工作区以同接口后续接入。
 */
public interface Workspace {

    /** LOCAL_WORKTREE（默认）/ REMOTE / CONTAINER（后两者预留） */
    String type();

    /** 本机可读的工作目录 */
    Path path();

    /** 工作分支 */
    String branch();

    /** 基准分支（diff/合并参考） */
    String baseBranch();

    /** 清理并释放工作区（force 语义：丢弃未提交改动） */
    void cleanup();
}
