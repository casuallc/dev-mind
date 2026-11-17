package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-109 FR-04/05 GitRepoOpsService 回归（真 git 子进程 + @TempDir，同 GitRemoteOpsCommitLogTest 先例）：
 * 无 .git 目录 400 可读提示 / limit 默认 30 且超上限截断 200 / 分支白名单拒绝 /
 * LOCAL 仓库 checkout 拒绝 / 提交字段解析（中文 UTF-8）/ CLONE 行 checkout 真实切分支。
 * 无 Spring 上下文：GitRepositoryRepository 用 JDK 动态代理内存 fake（同 GitRepoSyncServiceFetchTest）。
 */
class GitRepoOpsServiceTest {

    @TempDir
    Path tmp;

    private final Map<Long, GitRepositoryEntity> store = new HashMap<>();
    private long idSeq = 0;
    private GitRepoOpsService service;

    @BeforeEach
    void setUp() {
        service = new GitRepoOpsService(fakeRepoRepo());
    }

    @SuppressWarnings("unchecked")
    private GitRepositoryRepository fakeRepoRepo() {
        InvocationHandler handler = (p, m, args) -> switch (m.getName()) {
            case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
            default -> throw new UnsupportedOperationException(m.getName());
        };
        return (GitRepositoryRepository) Proxy.newProxyInstance(
                GitRepositoryRepository.class.getClassLoader(),
                new Class<?>[]{GitRepositoryRepository.class}, handler);
    }

    private GitRepositoryEntity addRepo(String sourceType, String cloneStatus, Path localPath,
                                        String defaultBranch, String branches) {
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setId(++idSeq);
        e.setName("r" + e.getId());
        e.setSourceType(sourceType);
        e.setCloneStatus(cloneStatus);
        e.setStatus(GitRepositoryEntity.STATUS_ACTIVE);
        e.setLocalPath(localPath == null ? null : localPath.toString());
        e.setDefaultBranch(defaultBranch);
        e.setBranches(branches);
        store.put(e.getId(), e);
        return e;
    }

    // ---------------- git 测试基建 ----------------

    /** 真 git 子进程：剥掉平台注入的 GIT_AUTHOR_/GIT_COMMITTER_ 前缀环境变量防污染，再显式指定身份。 */
    private static void git(Path cwd, String... args) {
        runGit(cwd, null, args);
    }

    private static void runGit(Path cwd, Map<String, String> extraEnv, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("GIT_AUTHOR") || k.startsWith("GIT_COMMITTER"));
        pb.environment().put("GIT_AUTHOR_NAME", "测试用户");
        pb.environment().put("GIT_AUTHOR_EMAIL", "tester@devmind.local");
        pb.environment().put("GIT_COMMITTER_NAME", "测试用户");
        pb.environment().put("GIT_COMMITTER_EMAIL", "tester@devmind.local");
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path initRepo(String name) {
        Path dir = tmp.resolve(name);
        git(tmp, "init", "-b", "main", dir.toString());
        return dir;
    }

    private void emptyCommit(Path repo, String message) {
        git(repo, "commit", "--allow-empty", "-q", "-m", message);
    }

    // ---------------- FR-04 提交历史 ----------------

    @Test
    void listCommitsParsesFieldsWithUtf8Subject() throws Exception {
        Path dir = initRepo("r1");
        Files.writeString(dir.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "初始化：加 a 文件");
        emptyCommit(dir, "空提交 second");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        List<RepoCommitView> rows = service.listCommits(repo.getId(), null, null);

        assertEquals(2, rows.size());
        // 最新在前
        assertEquals("空提交 second", rows.get(0).subject());
        assertEquals("初始化：加 a 文件", rows.get(1).subject());
        assertEquals(40, rows.get(0).sha().length());
        assertEquals("测试用户", rows.get(0).authorName());
        assertEquals("tester@devmind.local", rows.get(0).authorEmail());
        assertTrue(rows.get(0).time().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                () -> "时间格式: " + rows.get(0).time());
    }

    @Test
    void listCommitsRejectsMissingGitDirWithReadable400() {
        // LOCAL 路径已失效（目录都不存在）→ 400 可读提示，禁止裸 git 错误
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null,
                tmp.resolve("gone"), "main", "main");
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.listCommits(repo.getId(), null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("本地路径失效或克隆未就绪"), () -> e.getMessage());

        // 目录在但没有 .git（克隆半截/普通目录）同样 400
        Path notGit = tmp.resolve("not-git");
        assertTrue(notGit.toFile().mkdirs());
        GitRepositoryEntity repo2 = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, notGit, "main", "main");
        DevMindException e2 = assertThrows(DevMindException.class,
                () -> service.listCommits(repo2.getId(), null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e2.getErrorCode());
    }

    @Test
    void listCommitsRejectsBranchOutsideWhitelist() {
        Path dir = initRepo("r3");
        emptyCommit(dir, "c1");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main\ndev");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.listCommits(repo.getId(), "feature/x", null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("分支不在仓库分支列表中"), () -> e.getMessage());
    }

    @Test
    void listCommitsDefaultLimit30AndCap200() {
        // 210 个提交：默认 limit → 30；显式 2 → 2；显式 500 → 截断到硬上限 200
        Path dir = initRepo("r4");
        for (int i = 1; i <= 210; i++) {
            emptyCommit(dir, "c" + i);
        }
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        List<RepoCommitView> dft = service.listCommits(repo.getId(), null, null);
        assertEquals(30, dft.size());
        assertEquals("c210", dft.get(0).subject());

        List<RepoCommitView> two = service.listCommits(repo.getId(), null, 2);
        assertEquals(2, two.size());

        List<RepoCommitView> capped = service.listCommits(repo.getId(), null, 500);
        assertEquals(200, capped.size());
        assertEquals("c210", capped.get(0).subject());
        assertEquals("c11", capped.get(199).subject());
    }

    @Test
    void listCommitsUsesExplicitWhitelistedBranch() {
        Path dir = initRepo("r5");
        emptyCommit(dir, "main-1");
        git(dir, "checkout", "-q", "-b", "dev");
        emptyCommit(dir, "dev-only");
        git(dir, "checkout", "-q", "main");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main\ndev");

        List<RepoCommitView> devRows = service.listCommits(repo.getId(), "dev", null);
        assertEquals(2, devRows.size());
        assertEquals("dev-only", devRows.get(0).subject());

        List<RepoCommitView> mainRows = service.listCommits(repo.getId(), null, null);
        assertEquals(1, mainRows.size());
        assertEquals("main-1", mainRows.get(0).subject());
    }

    // ---------------- FR-05 分支切换 ----------------

    @Test
    void checkoutRejectsLocalRepo() {
        Path dir = initRepo("r6");
        emptyCommit(dir, "c1");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.checkout(repo.getId(), "main"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("本地路径仓库不支持"), () -> e.getMessage());
    }

    @Test
    void checkoutRejectsNotReadyClone() {
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_CLONE,
                GitRepositoryEntity.CLONE_CLONING, tmp.resolve("whatever"), "main", "main");
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.checkout(repo.getId(), "main"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void checkoutRejectsBranchOutsideWhitelist() {
        Path dir = initRepo("r8");
        emptyCommit(dir, "c1");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_CLONE,
                GitRepositoryEntity.CLONE_READY, dir, "main", "main");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.checkout(repo.getId(), "no-such"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void checkoutSwitchesCloneToRemoteBranch() {
        // bare origin 两条分支 + 真克隆 → checkout dev 后 HEAD 在 dev 且对齐 origin/dev
        Path origin = tmp.resolve("origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", "-q", origin.toString(), seed.toString());
        emptyCommit(seed, "main-1");
        git(seed, "push", "-q", "origin", "main");
        git(seed, "checkout", "-q", "-b", "dev");
        emptyCommit(seed, "dev-1");
        git(seed, "push", "-q", "origin", "dev");

        Path clone = tmp.resolve("clone");
        git(tmp, "clone", "-q", origin.toString(), clone.toString());

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_CLONE,
                GitRepositoryEntity.CLONE_READY, clone, "main", "main\ndev");
        service.checkout(repo.getId(), "dev");

        // 真实工作区已切到 dev
        List<String> cmd = List.of("git", "branch", "--show-current");
        try {
            Process p = new ProcessBuilder(cmd).directory(clone.toFile()).start();
            String current = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, p.waitFor());
            assertEquals("dev", current);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // 切过去后 dev 分支的提交历史可见
        List<RepoCommitView> rows = service.listCommits(repo.getId(), "dev", null);
        assertEquals("dev-1", rows.get(0).subject());
    }
}
