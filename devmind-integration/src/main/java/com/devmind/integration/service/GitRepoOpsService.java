package com.devmind.integration.service;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.util.GitCli;
import com.devmind.integration.dto.RepoCommitDetailView;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CAP-111 代码库提交日志只读操作（CAP-109 FR-04 演进；FR-05 分支切换已下线）：
 * <ul>
 *   <li>提交日志：git log（%x1f 字段分隔，同 GitLogScanner 口径），branch 走实体分支白名单
 *       防注入；服务端分页（page 从 0 起，size 默认 20 硬上限 100）——无关键字时
 *       {@code rev-list --count} 求总数 + {@code --skip/-n} 取页；有关键字时全量拉取内存
 *       过滤（subject 忽略大小写包含 / sha 忽略大小写前缀），因 git --grep 盖不住哈希维度；</li>
 *   <li>提交详情：{@code git show --no-patch} 取元信息+正文，{@code git show --name-status -z
 *       --first-parent} 取变更文件（NUL 分隔机读，merge 提交按第一父 diff）；sha 参数
 *       走 ^[0-9a-fA-F]{4,40}$ 白名单防注入。</li>
 * </ul>
 */
@Service
public class GitRepoOpsService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int GIT_TIMEOUT_SEC = 30;
    /** git log %x1f 字段分隔符（0x1f 单元分隔符） */
    private static final String FIELD_SEP = String.valueOf((char) 0x1f);
    /** 提交哈希白名单（完整或前缀，防 git 参数注入） */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{4,40}$");

    private final GitRepositoryRepository gitRepoRepo;

    public GitRepoOpsService(GitRepositoryRepository gitRepoRepo) {
        this.gitRepoRepo = gitRepoRepo;
    }

    // ---------------- 提交日志 ----------------

    /**
     * 分支提交日志分页：branch 缺省用实体默认分支（再缺省 HEAD）；显式传入必须在实体
     * 分支列表白名单内（防 git 参数注入），否则 400。keyword 匹配提交信息（忽略大小写
     * 包含）或提交哈希（忽略大小写前缀）。
     */
    public PageView<RepoCommitView> listCommits(Long id, String branch, String keyword,
                                                Integer page, Integer size) {
        GitRepositoryEntity repo = requireRepo(id);
        Path path = requireGitDir(repo);
        String ref = resolveBranch(repo, branch);
        String logRef = ref == null ? null : resolveExistingRef(path, ref);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String kw = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            long total = countCommits(path, logRef);
            List<String> args = logArgs(logRef);
            args.add("--skip=" + ((long) p * s));
            args.add("-n");
            args.add(String.valueOf(s));
            GitCli.Result r = GitCli.run(path, GIT_TIMEOUT_SEC, args.toArray(new String[0]));
            GitCli.requireOk(r, List.of("git", "log", logRef == null ? "HEAD" : logRef));
            return new PageView<>(parseCommits(r.out()), total, p, s);
        }
        // 关键字过滤：全量 log 内存过滤后切片（--grep 只匹配提交信息，盖不住 sha 维度）
        GitCli.Result r = GitCli.run(path, GIT_TIMEOUT_SEC,
                logArgs(logRef).toArray(new String[0]));
        GitCli.requireOk(r, List.of("git", "log", logRef == null ? "HEAD" : logRef));
        List<RepoCommitView> matched = parseCommits(r.out()).stream()
                .filter(c -> c.subject().toLowerCase(Locale.ROOT).contains(kw)
                        || c.sha().toLowerCase(Locale.ROOT).startsWith(kw))
                .toList();
        int from = Math.min((int) Math.min((long) p * s, Integer.MAX_VALUE), matched.size());
        int to = Math.min(from + s, matched.size());
        return new PageView<>(matched.subList(from, to), matched.size(), p, s);
    }

    /** git log 公共参数：ref（可空=HEAD）+ %x1f 五段格式 + 统一时间格式。 */
    private static List<String> logArgs(String logRef) {
        List<String> args = new ArrayList<>(List.of("git", "log"));
        if (logRef != null) {
            args.add(logRef);
        }
        args.add("--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s");
        args.add("--date=format:%Y-%m-%d %H:%M:%S");
        return args;
    }

    /** 分支提交总数（无关键字分页用）：git rev-list --count <ref|HEAD>。 */
    private static long countCommits(Path path, String logRef) {
        List<String> args = List.of("git", "rev-list", "--count", logRef == null ? "HEAD" : logRef);
        GitCli.Result r = GitCli.run(path, GIT_TIMEOUT_SEC, args.toArray(new String[0]));
        GitCli.requireOk(r, args);
        return Long.parseLong(r.out().strip());
    }

    // ---------------- 提交详情 ----------------

    /** 提交详情：完整元信息+正文+变更文件；sha 白名单校验，不存在/无法解析 400。 */
    public RepoCommitDetailView commitDetail(Long id, String sha) {
        GitRepositoryEntity repo = requireRepo(id);
        Path path = requireGitDir(repo);
        String s = sha == null ? "" : sha.strip();
        if (!SHA_PATTERN.matcher(s).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法提交哈希: " + s);
        }
        GitCli.Result meta = GitCli.run(path, GIT_TIMEOUT_SEC,
                "git", "show", "--no-patch", "--date=format:%Y-%m-%d %H:%M:%S",
                "--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s%x1f%b", s);
        GitCli.requireOk(meta, List.of("git", "show", s));
        String[] f = meta.out().split(FIELD_SEP, 6);
        if (f.length < 6) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "提交不存在或无法解析: " + s);
        }
        GitCli.Result files = GitCli.run(path, GIT_TIMEOUT_SEC,
                "git", "show", "--format=", "--name-status", "-z", "--first-parent", s);
        GitCli.requireOk(files, List.of("git", "show", s));
        return new RepoCommitDetailView(f[0], f[1], f[2], f[3], f[4], f[5].strip(),
                parseNameStatus(files.out()));
    }

    /**
     * name-status -z 输出机读解析：普通状态「M\0path\0」，改名/复制「R100\0old\0new\0」。
     * Java split 默认丢弃尾部空串，故末个 NUL 后无残留空 token。
     */
    private static List<RepoCommitDetailView.FileChange> parseNameStatus(String out) {
        List<RepoCommitDetailView.FileChange> rows = new ArrayList<>();
        String[] tokens = out.split("\0");
        for (int i = 0; i < tokens.length; i++) {
            String status = tokens[i];
            if (status.isBlank()) {
                continue;
            }
            char kind = status.charAt(0);
            if (kind == 'R' || kind == 'C') {
                if (i + 2 >= tokens.length) {
                    break;
                }
                rows.add(new RepoCommitDetailView.FileChange(status, tokens[i + 2], tokens[i + 1]));
                i += 2;
            } else {
                if (i + 1 >= tokens.length) {
                    break;
                }
                rows.add(new RepoCommitDetailView.FileChange(status, tokens[i + 1], null));
                i += 1;
            }
        }
        return rows;
    }

    /**
     * 解析出实际存在的 ref：CLONE 克隆的远端分支只在 origin/&lt;branch&gt; 有引用
     * （本地未 checkout 过时没有本地分支），先验本地名再退 origin/ 前缀。
     */
    private static String resolveExistingRef(Path path, String ref) {
        GitCli.Result local = GitCli.run(path, GIT_TIMEOUT_SEC,
                "git", "rev-parse", "--verify", "--quiet", ref);
        if (local.exitCode() == 0) {
            return ref;
        }
        GitCli.Result remote = GitCli.run(path, GIT_TIMEOUT_SEC,
                "git", "rev-parse", "--verify", "--quiet", "origin/" + ref);
        if (remote.exitCode() == 0) {
            return "origin/" + ref;
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "分支在本地克隆中不存在: " + ref + "（请先抓取刷新）");
    }

    /** %x1f 分隔五段：sha / 作者名 / 作者邮箱 / 时间 / 主题；空输出 = 空列表。 */
    private static List<RepoCommitView> parseCommits(String out) {
        List<RepoCommitView> rows = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(FIELD_SEP, -1);
            if (f.length < 5) {
                continue;
            }
            rows.add(new RepoCommitView(f[0], f[1], f[2], f[3], f[4]));
        }
        return rows;
    }

    // ---------------- 内部 ----------------

    private GitRepositoryEntity requireRepo(Long id) {
        return gitRepoRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "全局仓库不存在: " + id));
    }

    /** 本地 git 目录必须在位（含 .git），否则 400 可读提示而不是 git 报错 500。 */
    private static Path requireGitDir(GitRepositoryEntity repo) {
        String localPath = repo.getLocalPath();
        if (localPath == null || localPath.isBlank()
                || !Files.isDirectory(Path.of(localPath).resolve(".git"))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仓库本地路径失效或克隆未就绪（" + localPath + "），请检查登记路径或先完成克隆");
        }
        return Path.of(localPath);
    }

    /** branch 为空 → 实体默认分支 → 再空 → null（git log 默认 HEAD）。 */
    private String resolveBranch(GitRepositoryEntity repo, String branch) {
        if (branch == null || branch.isBlank()) {
            String def = repo.getDefaultBranch();
            return def == null || def.isBlank() ? null : def.strip();
        }
        String b = branch.strip();
        if (!branchesOf(repo).contains(b)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "分支不在仓库分支列表中（请先抓取刷新分支）: " + b);
        }
        return b;
    }

    /** branches 列换行分隔存储（同 GitRepoService.splitBranches 口径）。 */
    private static List<String> branchesOf(GitRepositoryEntity repo) {
        String branches = repo.getBranches();
        if (branches == null || branches.isBlank()) {
            return List.of();
        }
        return branches.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}
