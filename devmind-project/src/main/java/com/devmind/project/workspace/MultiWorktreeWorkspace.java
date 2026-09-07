package com.devmind.project.workspace;

import com.devmind.project.WorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * CAP-31 多仓库聚合工作区：一个会话关联多个 git 库时的隔离工作区。
 * 布局 = 聚合根（主库 .devmind/worktrees/&lt;sid&gt;）+ 各库 worktree 子目录 &lt;aggRoot&gt;/&lt;repoName&gt;；
 * claude cwd = 聚合根（在聚合根直接跑 git 命令无效，仓库在子目录 &lt;name&gt;/ 下——任务模板应说明）。
 */
public class MultiWorktreeWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(MultiWorktreeWorkspace.class);

    public static final String TYPE = "LOCAL_MULTI_WORKTREE";

    /** 一个仓库的 worktree 条目（清理按倒序逐个 remove）。 */
    public record Entry(String repoPath, String baseBranch, String branch, Path dir) {
    }

    private final WorktreeManager manager;
    private final Path aggRoot;
    private final String branch;
    private final String baseBranch;
    private final List<Entry> entries;

    public MultiWorktreeWorkspace(WorktreeManager manager, Path aggRoot, String branch,
                                  String baseBranch, List<Entry> entries) {
        this.manager = manager;
        this.aggRoot = aggRoot;
        this.branch = branch;
        this.baseBranch = baseBranch;
        this.entries = entries;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Path path() {
        return aggRoot;
    }

    @Override
    public String branch() {
        return branch;
    }

    @Override
    public String baseBranch() {
        return baseBranch;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 清理：倒序移除各库 worktree（force），再删聚合根目录（worktree 移除后应为空）。 */
    @Override
    public void cleanup() {
        for (Entry e : entries.reversed()) {
            manager.remove(e.repoPath(), e.branch(), e.dir());
        }
        if (!Files.exists(aggRoot)) {
            return;
        }
        try (var walk = Files.walk(aggRoot)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("聚合工作区根目录清理失败(可人工删除 {}): {}", aggRoot, e.getMessage());
        }
    }
}
