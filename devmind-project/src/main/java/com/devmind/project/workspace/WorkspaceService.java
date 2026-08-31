package com.devmind.project.workspace;

import com.devmind.project.WorktreeManager;
import com.devmind.project.model.Project;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 工作区分配入口（P1-3）：按类型准备/清理 Workspace。
 * 当前仅 LOCAL_WORKTREE；预留 REMOTE/CONTAINER（按项目配置或请求选择实现）。
 */
@Service
public class WorkspaceService {

    private final WorktreeManager worktreeManager;

    public WorkspaceService(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    /** 会话工作区：项目主库 + feature/<sessionId> 分支 */
    public Workspace prepareSessionWorkspace(Project project, String sessionId) {
        String branch = worktreeManager.branchFor(sessionId);
        Path dir = worktreeManager.worktreeDir(project.repoPath(), sessionId);
        worktreeManager.create(project.repoPath(), project.baseBranch(), branch, dir);
        return new LocalWorktreeWorkspace(worktreeManager, project.repoPath(), project.baseBranch(), branch, dir);
    }

    /** 任务工作区（P0-6 约定）：指定仓库 + task/<seq>-<slug> 分支，每 repo 一个 */
    public Workspace prepareTaskWorkspace(String repoPath, String baseBranch,
                                          long seq, String slug, String name) {
        String branch = worktreeManager.branchForTask(seq, slug);
        Path dir = worktreeManager.worktreeDir(repoPath, name);
        worktreeManager.create(repoPath, baseBranch, branch, dir);
        return new LocalWorktreeWorkspace(worktreeManager, repoPath, baseBranch, branch, dir);
    }

    /** 清理会话工作区（按记录的路径与约定分支重建句柄） */
    public void cleanupSessionWorkspace(Project project, String sessionId, Path worktreePath) {
        new LocalWorktreeWorkspace(worktreeManager, project.repoPath(), project.baseBranch(),
                worktreeManager.branchFor(sessionId), worktreePath).cleanup();
    }
}
