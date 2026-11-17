package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.util.GitCli;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CAP-109 FR-04/05 代码库 git 只读操作与分支切换：
 * <ul>
 *   <li>提交历史：git log（%x1f 字段分隔，同 GitLogScanner 口径），branch 走实体分支白名单
 *       防注入，limit 默认 30 硬上限 200；</li>
 *   <li>分支切换：仅 CLONE 且 READY 行，{@code checkout -B <branch> origin/<branch>} 对齐远端。
 *       <b>副作用提示（CAP-109 R1）</b>：该克隆目录被会话 diff / 工时扫描共用，切分支会改变
 *       工作区内容，相关消费方读到的是切换后的分支代码；LOCAL 行不替用户动本地工作区（400）。</li>
 * </ul>
 */
@Service
public class GitRepoOpsService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;
    private static final int GIT_TIMEOUT_SEC = 30;
    /** git log %x1f 字段分隔符（0x1f 单元分隔符） */
    private static final String FIELD_SEP = String.valueOf((char) 0x1f);

    private final GitRepositoryRepository gitRepoRepo;

    public GitRepoOpsService(GitRepositoryRepository gitRepoRepo) {
        this.gitRepoRepo = gitRepoRepo;
    }

    // ---------------- 提交历史 ----------------

    /**
     * 分支提交历史：branch 缺省用实体默认分支（再缺省 HEAD）；显式传入必须在实体
     * 分支列表白名单内（防 git 参数注入），否则 400。
     */
    public List<RepoCommitView> listCommits(Long id, String branch, Integer limit) {
        GitRepositoryEntity repo = requireRepo(id);
        Path path = requireGitDir(repo);
        String ref = resolveBranch(repo, branch);
        int n = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<String> args = new ArrayList<>(List.of("git", "log"));
        if (ref != null) {
            args.add(ref);
        }
        args.add("--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s");
        args.add("--date=format:%Y-%m-%d %H:%M:%S");
        args.add("-n");
        args.add(String.valueOf(n));
        GitCli.Result r = GitCli.run(path, GIT_TIMEOUT_SEC, args.toArray(new String[0]));
        GitCli.requireOk(r, List.of("git", "log", ref == null ? "HEAD" : ref));
        return parseCommits(r.out());
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

    // ---------------- 分支切换 ----------------

    /**
     * 切换服务端克隆的本地检出分支并对齐远端：仅 CLONE + READY；branch 必填且须在
     * 实体分支白名单内。克隆目录被会话 diff / 工时扫描共用，切分支是全局可见副作用。
     */
    public void checkout(Long id, String branch) {
        GitRepositoryEntity repo = requireRepo(id);
        if (!GitRepositoryEntity.SOURCE_CLONE.equals(repo.getSourceType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "本地路径仓库不支持平台切换分支（平台不替用户改本地工作区）: " + id);
        }
        if (!GitRepositoryEntity.CLONE_READY.equals(repo.getCloneStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "仓库克隆未就绪（" + repo.getCloneStatus() + "），请先克隆成功: " + id);
        }
        String b = branch == null ? "" : branch.strip();
        if (b.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "branch 不能为空");
        }
        if (!branchesOf(repo).contains(b)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "分支不在仓库分支列表中（请先抓取刷新分支）: " + b);
        }
        Path path = requireGitDir(repo);
        GitCli.Result r = GitCli.run(path, GIT_TIMEOUT_SEC,
                "git", "checkout", "-B", b, "origin/" + b);
        GitCli.requireOk(r, List.of("git", "checkout", b));
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
