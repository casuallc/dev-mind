package com.devmind.project.workspace;

import com.devmind.project.WorktreeManager;

import java.nio.file.Path;

/**
 * 本地 git worktree 工作区（P1-3 默认实现）：生命周期委托 {@link WorktreeManager}。
 */
public class LocalWorktreeWorkspace implements Workspace {

    public static final String TYPE = "LOCAL_WORKTREE";

    private final WorktreeManager manager;
    private final String repoPath;
    private final String baseBranch;
    private final String branch;
    private final Path path;

    public LocalWorktreeWorkspace(WorktreeManager manager, String repoPath,
                                  String baseBranch, String branch, Path path) {
        this.manager = manager;
        this.repoPath = repoPath;
        this.baseBranch = baseBranch;
        this.branch = branch;
        this.path = path;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public String branch() {
        return branch;
    }

    @Override
    public String baseBranch() {
        return baseBranch;
    }

    @Override
    public void cleanup() {
        manager.remove(repoPath, branch, path);
    }
}
