package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>P0-4 多库模型：核心方法按"仓库路径 + 基准分支 + 分支名"显式入参，不再绑定单库项目；
 * 项目内每个 repo 可独立建 worktree（如需求分支 req/&lt;seq&gt;-&lt;slug&gt;）。{@link Project} 便捷重载保留，
 * 按项目主库（repoPath 镜像）解析。</p>
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    private final WorktreeProperties props;
    /** CAP-26 可选 SPI：integration 装配时 fetch 走凭据注入（私有 CLONE 库可刷新基线） */
    private final ObjectProvider<RepoGitGateway> fetchGateway;

    public WorktreeManager(WorktreeProperties props, ObjectProvider<RepoGitGateway> fetchGateway) {
        this.props = props;
        this.fetchGateway = fetchGateway;
    }

    /** 会话分支名：feature/<sid> */
    public String branchFor(String sessionId) {
        return "feature/" + sessionId;
    }

    /** 工作单元分支名（CAP-13 约定）：wi/<seq>-<slug>，每个 repo 一条 */
    public String branchForWorkItem(long seq, String slug) {
        return "wi/" + seq + (slug == null || slug.isBlank() ? "" : "-" + slug);
    }

    /** worktree 目录：root 配置优先，否则仓库内 .devmind/worktrees/<name> */
    public Path worktreeDir(String repoPath, String name) {
        if (props.getRoot() != null && !props.getRoot().isBlank()) {
            return Path.of(props.getRoot(), name).toAbsolutePath().normalize();
        }
        return Path.of(repoPath, ".devmind", "worktrees", name).toAbsolutePath().normalize();
    }

    /** worktree 目录（项目主库便捷重载） */
    public Path worktreeDir(Project project, String sessionId) {
        return worktreeDir(project.repoPath(), sessionId);
    }

    /**
     * 建 worktree：先 best-effort fetch 保证基准分支存在，再 add。
     *
     * <p>CAP-26 基线修正：fetch 成功后基准改用 {@code FETCH_HEAD}（远端最新），不再用
     * 本地分支引用（`git fetch origin <base>` 不更新本地分支，本地 base 可能滞后）；
     * fetch 失败（离线/新仓库/无凭据）回退本地 base 保持现状。凭据 fetch 经
     * {@code RepoGitGateway} SPI（integration 装配时带 token，私有 CLONE 库可刷新）。</p>
     *
     * @param repoPath  目标仓库绝对路径
     * @param baseBranch 基准分支（空则取配置默认）
     * @param branch    新分支名
     * @param worktree  worktree 目标目录
     * @return worktree 绝对路径
     */
    public Path create(String repoPath, String baseBranch, String branch, Path worktree) {
        Path repo = Path.of(repoPath).toAbsolutePath().normalize();
        String base = baseBranch != null && !baseBranch.isBlank() ? baseBranch : props.getBaseBranch();

        // CAP-26：fetch 成功 → 基准 = FETCH_HEAD（远端最新）；失败 → 本地 base（现状回退）
        String baseRef = fetchBaseline(repo, base);

        Result add = run(repo, 60, "git", "worktree", "add", "-b", branch, worktree.toString(), baseRef);
        if (add.exit() != 0) {
            throw new DevMindException(ErrorCode.INTERNAL, "创建 worktree 失败: " + add.stderr());
        }
        log.info("worktree created: {} @ {} (branch {}, base {} {})", worktree, repo, branch, base, baseRef);
        return worktree;
    }

    /** fetch 成功返回 "FETCH_HEAD"，否则返回本地 base（best-effort，不阻塞） */
    private String fetchBaseline(Path repo, String base) {
        RepoGitGateway gw = fetchGateway == null ? null : fetchGateway.getIfAvailable();
        if (gw != null) {
            try {
                if (gw.fetch(repo.toString(), base, null)) {
                    return "FETCH_HEAD";
                }
                // false = 纯本地库（无 remoteUrl）：跳过 origin fetch 也无意义，直接用本地 base
                return base;
            } catch (Exception e) {
                log.warn("凭据 fetch 失败，回退 origin fetch: {}", e.getMessage());
            }
        }
        return run(repo, 30, "git", "fetch", "origin", base).exit() == 0 ? "FETCH_HEAD" : base;
    }

    /** 建会话 worktree（项目主库便捷重载，分支 feature/<sid>） */
    public Path create(Project project, String sessionId) {
        return create(project.repoPath(), project.baseBranch(), branchFor(sessionId),
                worktreeDir(project, sessionId));
    }

    /**
     * 移除 worktree（含关联分支）。force 语义：丢弃未提交改动。
     */
    public void remove(String repoPath, String branch, Path worktree) {
        Path repo = Path.of(repoPath).toAbsolutePath().normalize();
        if (worktree == null || !Files.exists(worktree)) {
            return;
        }
        try {
            run(repo, 60, "git", "worktree", "remove", "--force", worktree.toString());
            run(repo, 30, "git", "branch", "-D", branch);
            log.info("worktree removed: {}", worktree);
        } catch (DevMindException e) {
            log.warn("清理 worktree 失败(可手动处理): {} -> {}", worktree, e.getMessage());
        }
    }

    /** 移除会话 worktree（项目主库便捷重载） */
    public void remove(Project project, String sessionId, Path worktree) {
        remove(project.repoPath(), branchFor(sessionId), worktree);
    }

    /**
     * diff 摘要：<base>...HEAD 的 --stat 与变更文件清单。
     */
    public DiffResult diff(String baseBranch, Path worktree) {
        if (worktree == null || !Files.exists(worktree)) {
            return DiffResult.empty();
        }
        String base = baseBranch != null && !baseBranch.isBlank() ? baseBranch : props.getBaseBranch();
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

    /** diff 摘要（项目主库便捷重载） */
    public DiffResult diff(Project project, Path worktree) {
        return diff(project.baseBranch(), worktree);
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
