package com.devmind.worklog.service;

import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.dto.GitScanRepoDiag;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.common.util.GitCli;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * CAP-28 FR-04：本地 git log 扫描。遍历用户勾选的 ACTIVE 仓库，按用户署名过滤当日提交。
 *
 * <p>编码安全（Windows GBK 坑）：命令显式 {@code -c i18n.logOutputEncoding=UTF-8
 * --encoding=UTF-8}，stdout 由 {@link GitCli} 按 UTF-8 字节解码；
 * format 用 %x1f 字段分隔 + %x1e 记录分隔（提交信息可含 | 与换行）。</p>
 *
 * <p>author 过滤链：个人 Git 凭证 email（CAP-24，按 repo host 匹配）→ 仓库本地
 * {@code git config user.email}（LOCAL 行多为本人工作副本）→ 凭证/displayName
 * → 不过滤（warn，宁可多不可漏，导入靠人工预览勾选兜底）。</p>
 *
 * <p>{@link #scanDetailed} 附带每仓库诊断（跳过/失败原因、实际署名过滤串），
 * 供导入预览回答「勾选的仓库为什么没有提交出现」。</p>
 */
@Service
public class GitLogScanner {

    private static final Logger log = LoggerFactory.getLogger(GitLogScanner.class);

    /** %x1f 字段分隔 / %x1e 记录分隔：H an ae aI s */
    private static final String RECORD_SEP = "";
    private static final String FIELD_SEP = "";
    private static final String FORMAT = "--format=%H%x1f%an%x1f%ae%x1f%aI%x1f%s%x1e";

    private final CodeRepoService codeRepoService;
    private final WorklogEntryRepository entryRepo;
    private final WorklogProperties props;
    private final ObjectProvider<GitIdentityProvider> identityProvider;

    public GitLogScanner(CodeRepoService codeRepoService,
                         WorklogEntryRepository entryRepo,
                         WorklogProperties props,
                         ObjectProvider<GitIdentityProvider> identityProvider) {
        this.codeRepoService = codeRepoService;
        this.entryRepo = entryRepo;
        this.props = props;
        this.identityProvider = identityProvider;
    }

    /** 扫描该用户勾选仓库在 date 当日的提交（本机时区），仅返回提交列表（日报生成用）。 */
    public List<GitCommitView> scan(String username, LocalDate date) {
        return scanDetailed(username, date).commits();
    }

    /** 单日扫描 + 每仓库诊断（等价于 from == to 的范围扫描）。 */
    public GitPreviewResponse scanDetailed(String username, LocalDate date) {
        return scanDetailed(username, date, date);
    }

    /** 范围扫描 [from, to]（本机时区，含首尾日），另附每个勾选仓库的扫描诊断（导入预览用）。 */
    public GitPreviewResponse scanDetailed(String username, LocalDate from, LocalDate to) {
        List<GitCommitView> out = new ArrayList<>();
        List<GitScanRepoDiag> diags = new ArrayList<>();
        for (GitRepoCatalog.RepoRef repo : codeRepoService.subscribedRepos(username)) {
            // 状态常量归 project 模块实体，此处用字面量防跨模块依赖
            if (!"ACTIVE".equals(repo.status())) {
                diags.add(new GitScanRepoDiag(repo.id(), repo.name(), "SKIPPED", null, "仓库已停用", 0));
                continue;
            }
            // CAP-29：服务端克隆未就绪（CLONING/FAILED）的行跳过；NONE=LOCAL 行直接可扫
            if ("CLONING".equals(repo.cloneStatus()) || "FAILED".equals(repo.cloneStatus())) {
                diags.add(new GitScanRepoDiag(repo.id(), repo.name(), "SKIPPED", null,
                        "服务端克隆未就绪（" + repo.cloneStatus() + "），请到后台「代码仓库」确认克隆状态", 0));
                continue;
            }
            try {
                RepoScan r = scanRepo(username, repo, from, to);
                out.addAll(r.commits());
                diags.add(r.diag());
            } catch (Exception e) {
                // 单库失败不拖垮整体（仓库可能被删/移动），记 warn 继续
                log.warn("git 扫描失败，已跳过: repo={}({}) err={}", repo.name(), repo.localPath(), e.getMessage());
                diags.add(new GitScanRepoDiag(repo.id(), repo.name(), "FAILED", null,
                        "扫描异常: " + e.getMessage(), 0));
            }
        }
        out.sort((a, b) -> {
            int c = a.committedAt().compareTo(b.committedAt());
            return c != 0 ? c : a.repoName().compareTo(b.repoName());
        });
        return new GitPreviewResponse(out, diags);
    }

    private record RepoScan(List<GitCommitView> commits, GitScanRepoDiag diag) {}

    private RepoScan scanRepo(String username, GitRepoCatalog.RepoRef repo, LocalDate from, LocalDate to) {
        List<String> args = new ArrayList<>(List.of(
                "git", "-c", "i18n.logOutputEncoding=UTF-8",
                "log", "--encoding=UTF-8", "--no-merges", FORMAT,
                "--since=" + from + " 00:00:00", "--until=" + to + " 23:59:59",
                "-n", String.valueOf(props.getGitScanMaxCommits())));
        String author = resolveAuthorFilter(username, repo);
        if (author != null) {
            args.add("--author=" + author);
        }
        if (repo.defaultBranch() != null && !repo.defaultBranch().isBlank()) {
            args.add(repo.defaultBranch().strip());
        }
        GitCli.Result r = GitCli.run(Path.of(repo.localPath()), 30, args.toArray(new String[0]));
        if (r.exitCode() != 0) {
            String err = r.err() == null ? "" : r.err().strip();
            log.warn("git log 失败: repo={} err={}", repo.name(), err);
            return new RepoScan(List.of(), new GitScanRepoDiag(repo.id(), repo.name(), "FAILED", author,
                    "git log 失败（" + abbrev(err) + "）——检查默认分支与本地路径", 0));
        }
        List<GitCommitView> commits = new ArrayList<>();
        for (String rec : r.out().split(RECORD_SEP)) {
            String[] f = rec.strip().split(FIELD_SEP, -1);
            if (f.length < 5 || f[0].isBlank()) {
                continue;
            }
            commits.add(new GitCommitView(repo.id(), repo.name(), f[0], f[1], f[2],
                    Instant.parse(f[3]), f[4],
                    entryRepo.existsByUserIdAndRepoIdAndCommitSha(username, repo.id(), f[0])));
        }
        String detail = author == null ? "未解析到署名，未按作者过滤（可能混入他人提交）"
                : commits.isEmpty()
                ? (from.equals(to) ? "当日" : "范围内") + "没有署名「" + author + "」的提交"
                : null;
        return new RepoScan(commits,
                new GitScanRepoDiag(repo.id(), repo.name(), "SCANNED", author, detail, commits.size()));
    }

    private static String abbrev(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /**
     * 解析 author 过滤串：个人凭证 email → 仓库本地 user.email → 凭证/displayName；
     * 全部落空返回 null（不过滤 + warn）。
     */
    private String resolveAuthorFilter(String username, GitRepoCatalog.RepoRef repo) {
        GitIdentityProvider provider = identityProvider.getIfAvailable();
        GitIdentityProvider.GitAuthor resolved = null;
        if (provider != null) {
            String host = hostOf(repo.remoteUrl());
            if (host == null) {
                host = hostOf(remoteUrlFromGit(repo));
            }
            resolved = provider.resolveAuthor(username, host).orElse(null);
        } else {
            log.warn("GitIdentityProvider 未装配，仓库 {} 仅按本地 git 配置过滤", repo.name());
        }
        if (resolved != null && resolved.email() != null && !resolved.email().isBlank()) {
            return resolved.email();
        }
        // 本地回退：LOCAL 行多为本人工作副本，仓库内 git config user.email 即真实提交署名
        String localEmail = localGitEmail(repo);
        if (localEmail != null) {
            return localEmail;
        }
        if (resolved != null && resolved.name() != null && !resolved.name().isBlank()) {
            return resolved.name();
        }
        log.warn("未能解析用户 {} 在仓库 {} 的署名，不做 author 过滤", username, repo.name());
        return null;
    }

    /** 仓库本地 git config user.email（含全局配置）；取不到返回 null。 */
    private String localGitEmail(GitRepoCatalog.RepoRef repo) {
        try {
            GitCli.Result r = GitCli.run(Path.of(repo.localPath()), 10,
                    "git", "config", "--get", "user.email");
            if (r.exitCode() == 0 && r.out() != null && !r.out().isBlank()) {
                return r.out().strip();
            }
        } catch (Exception e) {
            log.debug("读取仓库 {} 本地 user.email 失败: {}", repo.name(), e.getMessage());
        }
        return null;
    }

    private String remoteUrlFromGit(GitRepoCatalog.RepoRef repo) {
        try {
            GitCli.Result r = GitCli.run(Path.of(repo.localPath()), 10,
                    "git", "remote", "get-url", "origin");
            return r.exitCode() == 0 ? r.out().strip() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 remoteUrl 解析 host；支持 https:// 与 git@host:path 两种形态。 */
    static String hostOf(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String u = remoteUrl.strip();
        try {
            if (u.contains("://")) {
                return URI.create(u).getHost();
            }
            int at = u.indexOf('@');
            int colon = u.indexOf(':', Math.max(at, 0));
            if (at >= 0 && colon > at) {
                return u.substring(at + 1, colon);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
