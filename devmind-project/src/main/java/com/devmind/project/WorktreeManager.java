package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git worktree 增删与 diff 摘要。全部走 git CLI（Windows 兼容），会话隔离靠每次新建分支。
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    private final WorktreeProperties props;

    public WorktreeManager(WorktreeProperties props) {
        this.props = props;
    }

    /** 会话分支名：feature/<sid> */
    public String branchFor(String sessionId) {
        return "feature/" + sessionId;
    }

    /** worktree 目录：root 配置优先，否则项目内 .devmind/worktrees/<sid> */
    public Path worktreeDir(Project project, String sessionId) {
        if (props.getRoot() != null && !props.getRoot().isBlank()) {
            return Path.of(props.getRoot(), sessionId).toAbsolutePath().normalize();
        }
        return Path.of(project.repoPath(), ".devmind", "worktrees", sessionId).toAbsolutePath().normalize();
    }

    /**
     * 建 worktree：先 best-effort fetch 保证基准分支存在，再 add。
     *
     * @return worktree 绝对路径
     */
    public Path create(Project project, String sessionId) {
        Path repo = Path.of(project.repoPath()).toAbsolutePath().normalize();
        Path wt = worktreeDir(project, sessionId);
        String branch = branchFor(sessionId);
        String base = project.baseBranch() != null && !project.baseBranch().isBlank()
                ? project.baseBranch() : props.getBaseBranch();

        // 基准分支保证：fetch 失败（离线/新仓库）不阻塞，留给 add 报错
        run(repo, 30, "git", "fetch", "origin", base);

        Result add = run(repo, 60, "git", "worktree", "add", "-b", branch, wt.toString(), base);
        if (add.exit() != 0) {
            throw new DevMindException(ErrorCode.INTERNAL, "创建 worktree 失败: " + add.stderr());
        }
        log.info("worktree created: {} @ {} (branch {}, base {})", wt, repo, branch, base);
        return wt;
    }

    /**
     * 移除 worktree（含关联分支）。force 用于丢弃未提交改动。
     */
    public void remove(Project project, String sessionId, Path worktree) {
        Path repo = Path.of(project.repoPath()).toAbsolutePath().normalize();
        if (worktree == null || !Files.exists(worktree)) {
            return;
        }
        try {
            run(repo, 60, "git", "worktree", "remove", "--force", worktree.toString());
            run(repo, 30, "git", "branch", "-D", branchFor(sessionId));
            log.info("worktree removed: {}", worktree);
        } catch (DevMindException e) {
            log.warn("清理 worktree 失败(可手动处理): {} -> {}", worktree, e.getMessage());
        }
    }

    /**
     * diff 摘要：<base>...HEAD 的 --stat 与变更文件清单。
     */
    public DiffResult diff(Project project, Path worktree) {
        if (worktree == null || !Files.exists(worktree)) {
            return DiffResult.empty();
        }
        String base = project.baseBranch() != null && !project.baseBranch().isBlank()
                ? project.baseBranch() : props.getBaseBranch();
        Result stat = run(worktree, 30, "git", "diff", "--stat", base + "...HEAD");
        Result nameOnly = run(worktree, 30, "git", "diff", "--name-only", base + "...HEAD");
        List<String> files = nameOnly.stdout().lines()
                .map(String::trim).filter(l -> !l.isBlank()).toList();
        // 新建文件未跟踪，git diff 看不到，需单独列出（排除 .gitignore 命中）
        Result untracked = run(worktree, 30, "git", "ls-files", "--others", "--exclude-standard");
        List<String> untrackedFiles = untracked.stdout().lines()
                .map(String::trim).filter(l -> !l.isBlank())
                .map(l -> "?? " + l).toList();
        StringBuilder sb = new StringBuilder(stat.stdout());
        if (!untrackedFiles.isEmpty()) {
            for (String u : untrackedFiles) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(u, 3, u.length()).append(" | (new file, untracked)");
            }
        }
        List<String> allFiles = new ArrayList<>(files);
        allFiles.addAll(untrackedFiles);
        return new DiffResult(sb.toString(), allFiles);
    }

    public record DiffResult(String stat, List<String> files) {
        public static DiffResult empty() {
            return new DiffResult("", List.of());
        }
        public boolean hasChanges() {
            return files != null && !files.isEmpty();
        }
    }

    private record Result(int exit, String stdout, String stderr) {}

    private Result run(Path cwd, int timeoutSec, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DevMindException(ErrorCode.INTERNAL, "命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令执行失败: " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令被中断: " + String.join(" ", args));
        }
    }
}
