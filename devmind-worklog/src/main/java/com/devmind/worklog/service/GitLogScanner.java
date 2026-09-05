package com.devmind.worklog.service;

import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.GitCommitView;
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
 * <p>author 过滤链：repo.remote_url（缺省 git remote get-url origin）取 host →
 * {@link GitIdentityProvider#resolveAuthor}（ObjectProvider 探测，integration 缺席时降级）
 * → {@code --author=<email>}（email 空退 name）；解析为空时不过滤并 warn，
 * 宁可多不可漏，导入靠人工预览勾选兜底。</p>
 */
@Service
public class GitLogScanner {

    private static final Logger log = LoggerFactory.getLogger(GitLogScanner.class);

    /** %x1f 字段分隔 / %x1e 记录分隔：H an ae aI s */
    private static final String RECORD_SEP = "\u001e";
    private static final String FIELD_SEP = "\u001f";
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

    /** 扫描该用户勾选仓库在 date 当日的提交（本机时区）。 */
    public List<GitCommitView> scan(String username, LocalDate date) {
        List<GitCommitView> out = new ArrayList<>();
        for (GitRepoCatalog.RepoRef repo : codeRepoService.subscribedActiveRepos(username)) {
            // CAP-29：服务端克隆未就绪（CLONING/FAILED）的行跳过；NONE=LOCAL 行直接可扫
            if ("CLONING".equals(repo.cloneStatus()) || "FAILED".equals(repo.cloneStatus())) {
                log.debug("仓库克隆未就绪，扫描跳过: repo={} cloneStatus={}", repo.name(), repo.cloneStatus());
                continue;
            }
            try {
                out.addAll(scanRepo(username, repo, date));
            } catch (Exception e) {
                // 单库失败不拖垮整体（仓库可能被删/移动），记 warn 继续
                log.warn("git 扫描失败，已跳过: repo={}({}) err={}", repo.name(), repo.localPath(), e.getMessage());
            }
        }
        out.sort((a, b) -> {
            int c = a.committedAt().compareTo(b.committedAt());
            return c != 0 ? c : a.repoName().compareTo(b.repoName());
        });
        return out;
    }

    private List<GitCommitView> scanRepo(String username, GitRepoCatalog.RepoRef repo, LocalDate date) {
        List<String> args = new ArrayList<>(List.of(
                "git", "-c", "i18n.logOutputEncoding=UTF-8",
                "log", "--encoding=UTF-8", "--no-merges", FORMAT,
                "--since=" + date + " 00:00:00", "--until=" + date + " 23:59:59",
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
            log.warn("git log 失败: repo={} err={}", repo.name(), r.err() == null ? "" : r.err().strip());
            return List.of();
        }
        List<GitCommitView> out = new ArrayList<>();
        for (String rec : r.out().split(RECORD_SEP)) {
            String[] f = rec.strip().split(FIELD_SEP, -1);
            if (f.length < 5 || f[0].isBlank()) {
                continue;
            }
            out.add(new GitCommitView(repo.id(), repo.name(), f[0], f[1], f[2],
                    Instant.parse(f[3]), f[4],
                    entryRepo.existsByUserIdAndRepoIdAndCommitSha(username, repo.id(), f[0])));
        }
        return out;
    }

    /** 解析 author 过滤串（email 优先，退 name）；无法解析返回 null（不过滤 + warn）。 */
    private String resolveAuthorFilter(String username, GitRepoCatalog.RepoRef repo) {
        GitIdentityProvider provider = identityProvider.getIfAvailable();
        if (provider == null) {
            log.warn("GitIdentityProvider 未装配，仓库 {} 不做 author 过滤", repo.name());
            return null;
        }
        String host = hostOf(repo.remoteUrl());
        if (host == null) {
            host = hostOf(remoteUrlFromGit(repo));
        }
        return provider.resolveAuthor(username, host)
                .map(a -> a.email() != null && !a.email().isBlank() ? a.email() : a.name())
                .orElseGet(() -> {
                    log.warn("未能解析用户 {} 在仓库 {} 的署名，不做 author 过滤", username, repo.name());
                    return null;
                });
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
