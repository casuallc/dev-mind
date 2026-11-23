package com.devmind.integration.service;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.dto.RepoCommitDetailView;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.integration.dto.RepoFileContentView;
import com.devmind.integration.dto.RepoFileDiffView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-111 GitRepoOpsService 回归（真 git 子进程 + @TempDir，沿用 CAP-109 测试基建）：
 * 无 .git 目录 400 可读提示 / 服务端分页跨页切片与总数 / size 上限截断 / 关键字文本与
 * 哈希过滤 / 分支白名单拒绝 / 提交详情（正文 + A/M/D/R 文件状态 + 非法 sha 400）。
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
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("GIT_AUTHOR") || k.startsWith("GIT_COMMITTER"));
        pb.environment().put("GIT_AUTHOR_NAME", "测试用户");
        pb.environment().put("GIT_AUTHOR_EMAIL", "tester@devmind.local");
        pb.environment().put("GIT_COMMITTER_NAME", "测试用户");
        pb.environment().put("GIT_COMMITTER_EMAIL", "tester@devmind.local");
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

    // ---------------- 提交日志：字段与校验 ----------------

    @Test
    void listCommitsParsesFieldsWithUtf8Subject() throws Exception {
        Path dir = initRepo("r1");
        Files.writeString(dir.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "初始化：加 a 文件");
        emptyCommit(dir, "空提交 second");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        PageView<RepoCommitView> page = service.listCommits(repo.getId(), null, null, null, null);

        assertEquals(2, page.total());
        assertEquals(0, page.page());
        assertEquals(20, page.size());
        List<RepoCommitView> rows = page.items();
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
                () -> service.listCommits(repo.getId(), null, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("本地路径失效或克隆未就绪"), () -> e.getMessage());

        // 目录在但没有 .git（克隆半截/普通目录）同样 400
        Path notGit = tmp.resolve("not-git");
        assertTrue(notGit.toFile().mkdirs());
        GitRepositoryEntity repo2 = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, notGit, "main", "main");
        DevMindException e2 = assertThrows(DevMindException.class,
                () -> service.listCommits(repo2.getId(), null, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e2.getErrorCode());
    }

    @Test
    void listCommitsRejectsBranchOutsideWhitelist() {
        Path dir = initRepo("r3");
        emptyCommit(dir, "c1");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main\ndev");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.listCommits(repo.getId(), "feature/x", null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("分支不在仓库分支列表中"), () -> e.getMessage());
    }

    @Test
    void listCommitsUsesExplicitWhitelistedBranch() {
        Path dir = initRepo("r5");
        emptyCommit(dir, "main-1");
        git(dir, "checkout", "-q", "-b", "dev");
        emptyCommit(dir, "dev-only");
        git(dir, "checkout", "-q", "main");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main\ndev");

        PageView<RepoCommitView> devRows = service.listCommits(repo.getId(), "dev", null, null, null);
        assertEquals(2, devRows.total());
        assertEquals("dev-only", devRows.items().get(0).subject());

        PageView<RepoCommitView> mainRows = service.listCommits(repo.getId(), null, null, null, null);
        assertEquals(1, mainRows.total());
        assertEquals("main-1", mainRows.items().get(0).subject());
    }

    @Test
    void listCommitsFallsBackToOriginRefWhenLocalBranchMissing() {
        // CLONE 克隆：dev 只以 origin/dev 远端引用存在（本地未 checkout 过），
        // git log dev 会报 unknown revision —— 须回退 origin/dev（E2E 实炸点）
        Path origin = tmp.resolve("origin2.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed2");
        git(tmp, "clone", "-q", origin.toString(), seed.toString());
        emptyCommit(seed, "main-1");
        git(seed, "push", "-q", "origin", "main");
        git(seed, "checkout", "-q", "-b", "dev");
        emptyCommit(seed, "dev-only");
        git(seed, "push", "-q", "origin", "dev");

        Path clone = tmp.resolve("clone2");
        git(tmp, "clone", "-q", origin.toString(), clone.toString());
        // 克隆只有本地 main，dev 在 origin/dev
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_CLONE,
                GitRepositoryEntity.CLONE_READY, clone, "main", "main\ndev");

        PageView<RepoCommitView> devRows = service.listCommits(repo.getId(), "dev", null, null, null);
        assertEquals(2, devRows.total());
        assertEquals("dev-only", devRows.items().get(0).subject());

        PageView<RepoCommitView> mainRows = service.listCommits(repo.getId(), null, null, null, null);
        assertEquals(1, mainRows.total());
        assertEquals("main-1", mainRows.items().get(0).subject());
    }

    // ---------------- 提交日志：分页 ----------------

    @Test
    void listCommitsPaginatesAcrossAllCommits() {
        // 25 个提交：默认 size=20 → 第 0 页 20 条、第 1 页 5 条，total 恒 25（可看到全部提交）
        Path dir = initRepo("r4");
        for (int i = 1; i <= 25; i++) {
            emptyCommit(dir, "c" + i);
        }
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        PageView<RepoCommitView> p0 = service.listCommits(repo.getId(), null, null, 0, null);
        assertEquals(25, p0.total());
        assertEquals(20, p0.items().size());
        assertEquals("c25", p0.items().get(0).subject());

        PageView<RepoCommitView> p1 = service.listCommits(repo.getId(), null, null, 1, 20);
        assertEquals(25, p1.total());
        assertEquals(5, p1.items().size());
        assertEquals("c5", p1.items().get(0).subject());
        assertEquals("c1", p1.items().get(4).subject());

        // 显式小页 + 越界页 → 空页不报错
        PageView<RepoCommitView> sized = service.listCommits(repo.getId(), null, null, 1, 10);
        assertEquals(10, sized.items().size());
        assertEquals("c15", sized.items().get(0).subject());
        PageView<RepoCommitView> overflow = service.listCommits(repo.getId(), null, null, 9, 10);
        assertEquals(25, overflow.total());
        assertTrue(overflow.items().isEmpty());
    }

    @Test
    void listCommitsCapsSizeAt100() {
        Path dir = initRepo("r6");
        for (int i = 1; i <= 105; i++) {
            emptyCommit(dir, "c" + i);
        }
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        PageView<RepoCommitView> capped = service.listCommits(repo.getId(), null, null, 0, 500);
        assertEquals(100, capped.size());
        assertEquals(100, capped.items().size());
        assertEquals(105, capped.total());
        assertEquals("c105", capped.items().get(0).subject());
    }

    // ---------------- 提交日志：搜索过滤 ----------------

    @Test
    void listCommitsFiltersByKeywordTextOrHash() {
        Path dir = initRepo("r7");
        emptyCommit(dir, "修复登录超时");
        emptyCommit(dir, "登录页样式调整");
        emptyCommit(dir, "add login metrics");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        // 文本：忽略大小写包含（中英均可）
        PageView<RepoCommitView> byText = service.listCommits(repo.getId(), null, "登录", null, null);
        assertEquals(2, byText.total());
        assertEquals("登录页样式调整", byText.items().get(0).subject());
        PageView<RepoCommitView> byUpper = service.listCommits(repo.getId(), null, "LOGIN", null, null);
        assertEquals(1, byUpper.total());
        assertEquals("add login metrics", byUpper.items().get(0).subject());

        // 哈希：忽略大小写前缀
        String sha = byUpper.items().get(0).sha();
        PageView<RepoCommitView> byHash = service.listCommits(
                repo.getId(), null, sha.substring(0, 8).toUpperCase(), null, null);
        assertEquals(1, byHash.total());
        assertEquals(sha, byHash.items().get(0).sha());

        // 无命中 → 空页；过滤路径下分页同样生效
        PageView<RepoCommitView> none = service.listCommits(repo.getId(), null, "不存在的词", null, null);
        assertEquals(0, none.total());
        assertTrue(none.items().isEmpty());
        PageView<RepoCommitView> paged = service.listCommits(repo.getId(), null, "登录", 1, 1);
        assertEquals(2, paged.total());
        assertEquals(1, paged.items().size());
        assertEquals("修复登录超时", paged.items().get(0).subject());
    }

    // ---------------- 提交详情 ----------------

    @Test
    void commitDetailReturnsBodyAndChangedFiles() throws Exception {
        Path dir = initRepo("r8");
        Files.writeString(dir.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "初始化");
        // 第二次提交：改 a、删 b、改名 c→d，多行正文
        Files.writeString(dir.resolve("a.txt"), "v2", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.txt"), "bye", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt", "b.txt");
        git(dir, "commit", "-q", "-m", "加 b 文件");
        Files.writeString(dir.resolve("c.txt"), "see", StandardCharsets.UTF_8);
        git(dir, "add", "c.txt");
        git(dir, "commit", "-q", "-m", "加 c 文件");
        Files.writeString(dir.resolve("a.txt"), "v3", StandardCharsets.UTF_8);
        git(dir, "rm", "-q", "b.txt");
        git(dir, "mv", "c.txt", "d.txt");
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "变更：改 a 删 b 改名 c",
                "-m", "详细说明第一行\n详细说明第二行");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        RepoCommitDetailView d = service.commitDetail(repo.getId(), head);
        assertEquals(head, d.sha());
        assertEquals("变更：改 a 删 b 改名 c", d.subject());
        assertEquals("详细说明第一行\n详细说明第二行", d.body());
        assertEquals("测试用户", d.authorName());
        assertEquals(3, d.files().size());
        Map<String, RepoCommitDetailView.FileChange> byPath = new HashMap<>();
        d.files().forEach(f -> byPath.put(f.path(), f));
        assertEquals("M", byPath.get("a.txt").status());
        assertEquals("D", byPath.get("b.txt").status());
        assertTrue(byPath.get("d.txt").status().startsWith("R"), () -> byPath.get("d.txt").status());
        assertEquals("c.txt", byPath.get("d.txt").oldPath());

        // sha 前缀也能取详情
        RepoCommitDetailView byPrefix = service.commitDetail(repo.getId(), head.substring(0, 8));
        assertEquals(head, byPrefix.sha());

        // 根提交：全部文件为 A
        String root = service.listCommits(repo.getId(), null, null, 3, 1).items().get(0).sha();
        RepoCommitDetailView rootDetail = service.commitDetail(repo.getId(), root);
        assertEquals(1, rootDetail.files().size());
        assertEquals("A", rootDetail.files().get(0).status());
        assertEquals("a.txt", rootDetail.files().get(0).path());
        assertNull(rootDetail.files().get(0).oldPath());
    }

    @Test
    void commitDetailRejectsIllegalSha() {
        Path dir = initRepo("r9");
        emptyCommit(dir, "c1");
        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.commitDetail(repo.getId(), "--help"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("非法提交哈希"), () -> e.getMessage());

        // 合法形态但不存在 → 400 可读
        DevMindException e2 = assertThrows(DevMindException.class,
                () -> service.commitDetail(repo.getId(), "deadbeef"));
        assertEquals(ErrorCode.BAD_REQUEST, e2.getErrorCode());
    }

    // ---------------- CAP-113 单文件 diff ----------------

    @Test
    void fileDiffReturnsPatchAndNumstat() throws Exception {
        Path dir = initRepo("d1");
        Files.writeString(dir.resolve("a.txt"), "l1\nl2\nl3\n", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "init");
        // 改 1 行删 1 行加 2 行
        Files.writeString(dir.resolve("a.txt"), "l1\nl2x\nl4\n", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "modify a");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        RepoFileDiffView d = service.fileDiff(repo.getId(), head, "a.txt", null);
        assertEquals("a.txt", d.path());
        assertEquals(Integer.valueOf(2), d.additions());
        assertEquals(Integer.valueOf(2), d.deletions());
        assertFalse(d.binary());
        assertFalse(d.truncated());
        assertTrue(d.patch().contains("diff --git a/a.txt b/a.txt"), () -> d.patch());
        assertTrue(d.patch().contains("-l2"), () -> d.patch());
        assertTrue(d.patch().contains("+l2x"), () -> d.patch());
        assertTrue(d.patch().contains("@@"), () -> d.patch());

        // 根提交：新增文件全 add
        String root = service.listCommits(repo.getId(), null, null, 1, 1).items().get(0).sha();
        RepoFileDiffView rootDiff = service.fileDiff(repo.getId(), root, "a.txt", null);
        assertEquals(Integer.valueOf(3), rootDiff.additions());
        assertEquals(Integer.valueOf(0), rootDiff.deletions());
        assertTrue(rootDiff.patch().contains("new file mode"), () -> rootDiff.patch());

        // 未变更文件 → 400 可读
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.fileDiff(repo.getId(), head, "b.txt", null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("变更清单"), () -> e.getMessage());
    }

    @Test
    void fileDiffMatchesRenameByNewPath() throws Exception {
        Path dir = initRepo("d2");
        Files.writeString(dir.resolve("old.txt"), "x\n", StandardCharsets.UTF_8);
        git(dir, "add", "old.txt");
        git(dir, "commit", "-q", "-m", "init");
        git(dir, "mv", "old.txt", "new.txt");
        git(dir, "commit", "-q", "-m", "rename");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        // 按新路径过滤改名条目：numstat 与 patch 都能命中
        RepoFileDiffView d = service.fileDiff(repo.getId(), head, "new.txt", "old.txt");
        assertEquals("new.txt", d.path());
        assertTrue(d.patch().contains("rename from old.txt"), () -> d.patch());
        assertTrue(d.patch().contains("rename to new.txt"), () -> d.patch());
    }

    @Test
    void fileDiffFlagsBinaryAndRejectsBadParams() throws Exception {
        Path dir = initRepo("d3");
        Files.write(dir.resolve("bin.dat"), new byte[]{0x00, 0x01, 0x02, 0x00, 0x03});
        git(dir, "add", "bin.dat");
        git(dir, "commit", "-q", "-m", "add binary");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        RepoFileDiffView d = service.fileDiff(repo.getId(), head, "bin.dat", null);
        assertTrue(d.binary());
        assertNull(d.additions());
        assertNull(d.deletions());
        assertTrue(d.patch().contains("Binary files"), () -> d.patch());

        // 非法 sha / 非法路径 → 400
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.fileDiff(repo.getId(), "--help", "bin.dat", null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        DevMindException e2 = assertThrows(DevMindException.class,
                () -> service.fileDiff(repo.getId(), head, "  ", null));
        assertEquals(ErrorCode.BAD_REQUEST, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("非法文件路径"), () -> e2.getMessage());
    }

    // ---------------- CAP-113 单文件原文 ----------------

    @Test
    void fileContentReturnsFullTextAndSize() throws Exception {
        Path dir = initRepo("c1");
        Files.writeString(dir.resolve("a.txt"), "你好 v1\n", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("a.txt"), "你好 v2\n第二行\n", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "update");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        RepoFileContentView c = service.fileContent(repo.getId(), head, "a.txt", false);
        assertEquals("a.txt", c.path());
        assertEquals(head, c.ref());
        assertFalse(c.binary());
        assertFalse(c.truncated());
        assertEquals("你好 v2\n第二行\n", c.content());
        assertEquals(c.content().getBytes(StandardCharsets.UTF_8).length, c.size());

        // parent=true 取父提交版本
        RepoFileContentView p = service.fileContent(repo.getId(), head, "a.txt", true);
        assertEquals(head + "^", p.ref());
        assertEquals("你好 v1\n", p.content());

        // 文件在该提交不存在 → 400 可读
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.fileContent(repo.getId(), head, "gone.txt", false));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("不存在"), () -> e.getMessage());
    }

    @Test
    void fileContentFlagsBinaryAndTruncatesHuge() throws Exception {
        Path dir = initRepo("c2");
        Files.write(dir.resolve("bin.dat"), new byte[]{0x01, 0x00, 0x02, 0x03});
        // 600KB 文本 → 超 512KB 上限截断
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            big.append("0123456789abcdef\n");
        }
        Files.writeString(dir.resolve("big.txt"), big.toString(), StandardCharsets.UTF_8);
        git(dir, "add", "bin.dat", "big.txt");
        git(dir, "commit", "-q", "-m", "files");

        GitRepositoryEntity repo = addRepo(GitRepositoryEntity.SOURCE_LOCAL, null, dir, "main", "main");
        String head = service.listCommits(repo.getId(), null, null, null, null).items().get(0).sha();

        RepoFileContentView bin = service.fileContent(repo.getId(), head, "bin.dat", false);
        assertTrue(bin.binary());
        assertEquals("", bin.content());

        RepoFileContentView huge = service.fileContent(repo.getId(), head, "big.txt", false);
        assertTrue(huge.truncated());
        assertEquals("", huge.content());
        assertTrue(huge.size() > 512 * 1024);
    }
}
