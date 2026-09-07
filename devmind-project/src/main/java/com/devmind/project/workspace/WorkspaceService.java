package com.devmind.project.workspace;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.WorktreeManager;
import com.devmind.project.model.Project;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作区分配入口（P1-3）：按类型准备/清理 Workspace。
 * 当前仅 LOCAL_WORKTREE；预留 REMOTE/CONTAINER（按项目配置或请求选择实现）。
 *
 * <p>CAP-31 多库：会话工作区支持多仓库——单库保持现状（cwd=该库 worktree）；
 * 多库 = 聚合目录（主库 .devmind/worktrees/&lt;sid&gt; 作聚合根，各库 worktree 子目录
 * &lt;aggRoot&gt;/&lt;repoName&gt;，claude cwd=聚合根）。</p>
 */
@Service
public class WorkspaceService {

    private final WorktreeManager worktreeManager;

    public WorkspaceService(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    /** CAP-31 会话仓库参数（name 作聚合根下子目录名；repoPath 本地库绝对路径）。 */
    public record SessionRepoSpec(String name, String repoPath, String baseBranch) {
    }

    /** 会话工作区：项目主库 + feature/<sessionId> 分支 */
    public Workspace prepareSessionWorkspace(Project project, String sessionId) {
        String branch = worktreeManager.branchFor(sessionId);
        Path dir = worktreeManager.worktreeDir(project.repoPath(), sessionId);
        worktreeManager.create(project.repoPath(), project.baseBranch(), branch, dir);
        return new LocalWorktreeWorkspace(worktreeManager, project.repoPath(), project.baseBranch(), branch, dir);
    }

    /**
     * CAP-31 会话工作区（快照驱动）：单库 = 与 {@link #prepareSessionWorkspace(Project, String)}
     * 同布局同行为；多库 = 聚合目录（主库 worktree 父目录作聚合根，各库子目录 &lt;aggRoot&gt;/&lt;name&gt;）。
     * specs 顺序约定：主库在前（聚合根落在主库 .devmind 下）。
     */
    public Workspace prepareSessionWorkspace(List<SessionRepoSpec> specs, String sessionId) {
        if (specs == null || specs.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "会话仓库列表为空");
        }
        String branch = worktreeManager.branchFor(sessionId);
        if (specs.size() == 1) {
            SessionRepoSpec r = specs.get(0);
            Path dir = worktreeManager.worktreeDir(r.repoPath(), sessionId);
            worktreeManager.create(r.repoPath(), r.baseBranch(), branch, dir);
            return new LocalWorktreeWorkspace(worktreeManager, r.repoPath(), r.baseBranch(), branch, dir);
        }
        Path aggRoot = worktreeManager.worktreeDir(specs.get(0).repoPath(), sessionId);
        List<MultiWorktreeWorkspace.Entry> entries = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        try {
            for (SessionRepoSpec r : specs) {
                Path child = childDir(aggRoot, r.name(), usedNames);
                worktreeManager.create(r.repoPath(), r.baseBranch(), branch, child);
                entries.add(new MultiWorktreeWorkspace.Entry(r.repoPath(), r.baseBranch(), branch, child));
            }
        } catch (RuntimeException e) {
            // 中途失败：已建的 worktree 逐个回收，避免半成品残留
            new MultiWorktreeWorkspace(worktreeManager, aggRoot, branch,
                    specs.get(0).baseBranch(), entries).cleanup();
            throw e;
        }
        return new MultiWorktreeWorkspace(worktreeManager, aggRoot, branch,
                specs.get(0).baseBranch(), entries);
    }

    /** 工作单元工作区（CAP-13 约定）：指定仓库 + wi/<seq>-<slug> 分支，每 repo 一个 */
    public Workspace prepareWorkItemWorkspace(String repoPath, String baseBranch,
                                              long seq, String slug, String name) {
        String branch = worktreeManager.branchForWorkItem(seq, slug);
        Path dir = worktreeManager.worktreeDir(repoPath, name);
        worktreeManager.create(repoPath, baseBranch, branch, dir);
        return new LocalWorktreeWorkspace(worktreeManager, repoPath, baseBranch, branch, dir);
    }

    /** 清理会话工作区（按记录的路径与约定分支重建句柄） */
    public void cleanupSessionWorkspace(Project project, String sessionId, Path worktreePath) {
        new LocalWorktreeWorkspace(worktreeManager, project.repoPath(), project.baseBranch(),
                worktreeManager.branchFor(sessionId), worktreePath).cleanup();
    }

    /**
     * CAP-31 清理会话工作区（快照驱动）：单库退化为单库清理；多库按快照行重建
     * 聚合工作区句柄（子目录名推导与 prepare 同算法，确定性一致）。
     */
    public void cleanupSessionWorkspace(List<SessionRepoSpec> specs, String sessionId, Path rootPath) {
        String branch = worktreeManager.branchFor(sessionId);
        if (specs == null || specs.size() <= 1) {
            if (specs != null && !specs.isEmpty()) {
                SessionRepoSpec r = specs.get(0);
                new LocalWorktreeWorkspace(worktreeManager, r.repoPath(), r.baseBranch(), branch, rootPath)
                        .cleanup();
            }
            return;
        }
        List<MultiWorktreeWorkspace.Entry> entries = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (SessionRepoSpec r : specs) {
            entries.add(new MultiWorktreeWorkspace.Entry(r.repoPath(), r.baseBranch(), branch,
                    childDir(rootPath, r.name(), usedNames)));
        }
        new MultiWorktreeWorkspace(worktreeManager, rootPath, branch,
                specs.get(0).baseBranch(), entries).cleanup();
    }

    /** 聚合根下子目录名：库名 sanitize（非法字符→-），重名追加 -2/-3 后缀。prepare/cleanup 共用，勿改算法。 */
    private static Path childDir(Path aggRoot, String name, Set<String> usedNames) {
        String base = name == null || name.isBlank() ? "repo"
                : name.replaceAll("[^a-zA-Z0-9._-]", "-");
        String dir = base;
        int n = 2;
        while (!usedNames.add(dir)) {
            dir = base + "-" + n++;
        }
        return aggRoot.resolve(dir).normalize();
    }

    /** 按快照库名列表推导各库 worktree 子目录（与 prepare 同算法；diff 等只读场景用）。 */
    public static List<Path> childDirs(Path aggRoot, List<String> names) {
        List<Path> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String n : names) {
            out.add(childDir(aggRoot, n, used));
        }
        return out;
    }
}
