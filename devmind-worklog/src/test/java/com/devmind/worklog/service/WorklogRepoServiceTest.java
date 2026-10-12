package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.model.WorklogRepoMemberEntity;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogRepoMemberRepository;
import com.devmind.worklog.repo.WorklogRepoRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-74 {@link WorklogRepoService}：懒建仓幂等+并发、写入/commit/trailer/同内容零提交、
 * 列表排序与范围、跨年周（2025-12-29 → 2026-W01）、active_repo_key 路由、非成员 409。
 * 真 git 子进程（@TempDir）；DB 层 Mockito mock 背 ConcurrentHashMap。
 */
class WorklogRepoServiceTest {

    @TempDir
    Path repoRoot;

    private final Map<String, WorklogRepoEntity> repos = new ConcurrentHashMap<>();
    private final Map<String, WorklogRepoMemberEntity> members = new ConcurrentHashMap<>();
    private final Map<String, WorklogUserSettingsEntity> settings = new ConcurrentHashMap<>();
    private final AtomicLong repoIds = new AtomicLong(1);

    private WorklogRepoService service;
    private GitRemoteOps ops;

    @BeforeEach
    void setUp() {
        WorklogRepoRepository repoRepo = mock(WorklogRepoRepository.class);
        when(repoRepo.findByRepoKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(repos.get(inv.getArgument(0))));
        when(repoRepo.save(any(WorklogRepoEntity.class))).thenAnswer(inv -> {
            WorklogRepoEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(repoIds.getAndIncrement());
            }
            repos.put(e.getRepoKey(), e);
            return e;
        });

        WorklogRepoMemberRepository memberRepo = mock(WorklogRepoMemberRepository.class);
        when(memberRepo.findByRepoIdAndUserId(anyLong(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(members.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(memberRepo.save(any(WorklogRepoMemberEntity.class))).thenAnswer(inv -> {
            WorklogRepoMemberEntity e = inv.getArgument(0);
            members.put(e.getRepoId() + ":" + e.getUserId(), e);
            return e;
        });

        WorklogUserSettingsRepository settingsRepo = mock(WorklogUserSettingsRepository.class);
        when(settingsRepo.findByUserId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(settings.get(inv.getArgument(0))));

        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(repoRoot.toString());
        ops = new GitRemoteOps(nullIdentityProvider());
        service = new WorklogRepoService(props, repoRepo, memberRepo, settingsRepo, ops,
                nullIdentityProvider(), nullIdentityProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> nullIdentityProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private static String runGit(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
        return out.strip();
    }

    private Path aliceRepoDir() {
        return repoRoot.resolve(WorklogRepoService.personalRepoKey("alice"));
    }

    @Test
    void 懒建仓幂等且骨架commit齐全() throws Exception {
        WorklogRepoEntity r1 = service.ensurePersonalRepo("alice");
        WorklogRepoEntity r2 = service.ensurePersonalRepo("alice");
        assertEquals(r1.getId(), r2.getId());
        assertEquals(1, repos.size());
        Path dir = aliceRepoDir();
        assertTrue(Files.exists(dir.resolve("README.md")));
        assertTrue(Files.exists(dir.resolve("alice/daily/.gitkeep")));
        assertTrue(Files.exists(dir.resolve("alice/weekly/.gitkeep")));
        assertEquals("1", runGit(dir, "rev-list", "--count", "HEAD"));
        String body = runGit(dir, "-c", "i18n.logOutputEncoding=UTF-8", "log", "-1",
                "--encoding=UTF-8", "--format=%s%n%b");
        assertTrue(body.contains("worklog: init alice"), () -> "意外 subject: " + body);
        assertTrue(body.contains("DevMind-Source: init"), () -> "缺 trailer: " + body);
    }

    @Test
    void 并发建仓只建一次() throws Exception {
        int n = 8;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.ensurePersonalRepo("alice");
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            t.start();
            threads.add(t);
        }
        ready.await();
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(errors.isEmpty(), () -> "并发建仓异常: " + errors);
        assertEquals(1, repos.size());
        assertEquals("1", runGit(aliceRepoDir(), "rev-list", "--count", "HEAD"));
    }

    @Test
    void 写报告产生commit且trailer与署名正确() throws Exception {
        WorklogRepoService.WriteOutcome out = service.writeReport("alice",
                WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 12),
                "# 日报\n\n内容", WorklogRepoService.CommitSource.AI, "sess-123", null);
        assertTrue(out.committed());
        assertEquals("alice/daily/2026-10-12.md", out.relPath());
        Path dir = aliceRepoDir();
        assertEquals("# 日报\n\n内容", Files.readString(dir.resolve(out.relPath()), StandardCharsets.UTF_8));
        List<GitRemoteOps.PathCommit> hits = ops.lastCommitByPath(dir.toString(), List.of(out.relPath()), 10);
        assertEquals(1, hits.size());
        assertEquals("ai", hits.get(0).trailers().get("DevMind-Source"));
        assertEquals("sess-123", hits.get(0).trailers().get("DevMind-Session"));
        // 署名回退（无 identity provider）：username + <username>@devmind.local
        String author = runGit(dir, "log", "-1", "--format=%an <%ae>");
        assertEquals("alice <alice@devmind.local>", author);
    }

    @Test
    void 同内容零提交且内容变更产生新commit() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 12);
        assertTrue(service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, date,
                "v1", WorklogRepoService.CommitSource.AI, null, null).committed());
        // 同内容 → 幂等跳过
        assertFalse(service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, date,
                "v1", WorklogRepoService.CommitSource.AI, null, null).committed());
        assertEquals("2", runGit(aliceRepoDir(), "rev-list", "--count", "HEAD")); // 骨架 + v1
        // 手改 → 新 commit
        assertTrue(service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, date,
                "v1 手改", WorklogRepoService.CommitSource.MANUAL, null, null).committed());
        assertEquals("3", runGit(aliceRepoDir(), "rev-list", "--count", "HEAD"));
        var read = service.readReport("alice", WorklogRepoService.ReportKind.DAILY, date).orElseThrow();
        assertEquals("v1 手改", read.contentMd());
        assertEquals("manual", read.source());
        assertNotNull(read.updatedAt());
        assertNotNull(read.createdAt());
        assertTrue(read.createdAt().isBefore(read.updatedAt()) || read.createdAt().equals(read.updatedAt()));
    }

    @Test
    void 迁移commit日期回填updatedAt() throws Exception {
        Instant backfill = Instant.parse("2026-09-01T08:30:00Z");
        service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 9, 1),
                "迁移稿", WorklogRepoService.CommitSource.MIGRATION, null, backfill);
        var read = service.readReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 9, 1)).orElseThrow();
        assertEquals(backfill, read.updatedAt());
        assertEquals("migration", read.source());
    }

    @Test
    void 列表倒序范围过滤与preview() {
        service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 1),
                "第一天", WorklogRepoService.CommitSource.AI, null, null);
        service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 3),
                "第三天", WorklogRepoService.CommitSource.AI, null, null);
        service.writeReport("alice", WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 2),
                "第二天", WorklogRepoService.CommitSource.MANUAL, null, null);
        var all = service.listReports("alice", WorklogRepoService.ReportKind.DAILY, null, null, 100);
        assertEquals(3, all.size());
        assertEquals(LocalDate.of(2026, 10, 3), all.get(0).date());
        assertEquals(LocalDate.of(2026, 10, 1), all.get(2).date());
        assertEquals("第三天", all.get(0).preview());
        var ranged = service.listReports("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), 100);
        assertEquals(1, ranged.size());
        assertEquals("manual", ranged.get(0).source());
        // preview 截断
        var truncated = service.listReports("alice", WorklogRepoService.ReportKind.DAILY, null, null, 2);
        assertEquals("第三", truncated.get(0).preview());
    }

    @Test
    void 跨年周文件名与往返() throws Exception {
        // 2025-12-29 是 2026-W01 的周一（ISO 周年）
        assertEquals("2026-W01", WorklogRepoService.weekFileName(LocalDate.of(2025, 12, 29)));
        assertEquals(LocalDate.of(2025, 12, 29), WorklogRepoService.weekStartOf(2026, 1));
        LocalDate weekStart = LocalDate.of(2025, 12, 29);
        service.writeReport("alice", WorklogRepoService.ReportKind.WEEKLY, weekStart,
                "## 上周总结\n\ns\n\n## 下周计划\n\np", WorklogRepoService.CommitSource.AI, null, null);
        assertTrue(Files.exists(aliceRepoDir().resolve("alice/weekly/2026-W01.md")));
        assertTrue(service.reportExists("alice", WorklogRepoService.ReportKind.WEEKLY, weekStart));
        var weekly = service.listReports("alice", WorklogRepoService.ReportKind.WEEKLY, null, null, 100);
        assertEquals(1, weekly.size());
        assertEquals(weekStart, weekly.get(0).date());
        assertEquals("alice/weekly/2026-W01.md", weekly.get(0).relPath());
    }

    @Test
    void activeRepoKey路由到团队仓() throws Exception {
        // 仓主 bob 的仓（手工建行+成员，模拟 C6 团队仓）；alice 是成员且切活动仓
        WorklogRepoEntity team = new WorklogRepoEntity();
        team.setRepoKey("team-alpha");
        team.setRemoteBranch("main");
        team.setCreatedBy("bob");
        team.setCreatedAt(Instant.now());
        team.setId(repoIds.getAndIncrement());
        repos.put(team.getRepoKey(), team);
        for (String u : new String[]{"bob", "alice"}) {
            WorklogRepoMemberEntity m = new WorklogRepoMemberEntity();
            m.setRepoId(team.getId());
            m.setUserId(u);
            m.setSubdir(u);
            members.put(team.getId() + ":" + u, m);
        }
        ops.initRepo(repoRoot.resolve("team-alpha").toString(), "main");

        WorklogUserSettingsEntity s = new WorklogUserSettingsEntity();
        s.setUserId("alice");
        s.setActiveRepoKey("team-alpha");
        settings.put("alice", s);

        WorklogRepoService.WriteOutcome out = service.writeReport("alice",
                WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 12),
                "团队仓稿", WorklogRepoService.CommitSource.MANUAL, null, null);
        assertEquals("team-alpha", out.repoKey());
        assertTrue(Files.exists(repoRoot.resolve("team-alpha/alice/daily/2026-10-12.md")));
        // 个人仓未被创建
        assertFalse(Files.exists(aliceRepoDir().resolve(".git")));
        // active_repo_key 指向不存在的仓 → 回退个人仓
        s.setActiveRepoKey("ghost-repo");
        assertEquals(WorklogRepoService.personalRepoKey("alice"),
                service.activeRepoFor("alice").getRepoKey());
    }

    @Test
    void 非成员写入409且路径穿越被拒() {
        service.ensurePersonalRepo("alice");
        WorklogRepoEntity repo = repos.get(WorklogRepoService.personalRepoKey("alice"));
        // bob 不是 alice 个人仓成员
        DevMindException e = assertThrows(DevMindException.class, () ->
                service.writeReportTo(repo, "bob", WorklogRepoService.ReportKind.DAILY,
                        LocalDate.of(2026, 10, 12), "x", WorklogRepoService.CommitSource.MANUAL, null, null));
        assertEquals(409, e.getErrorCode().getStatus());
        // 路径穿越
        assertThrows(DevMindException.class, () ->
                WorklogRepoService.reportRelPath("../evil", WorklogRepoService.ReportKind.DAILY,
                        LocalDate.of(2026, 10, 12)));
        assertThrows(DevMindException.class, () -> service.repoDir("a/b"));
    }

    @Test
    void 读路径不建仓() {
        assertFalse(service.reportExists("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12)));
        assertTrue(service.readReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12)).isEmpty());
        assertTrue(service.listReports("alice", WorklogRepoService.ReportKind.DAILY,
                null, null, 100).isEmpty());
        assertTrue(repos.isEmpty());
    }

    // ---------------- CAP-74 M3：通用文件原语 ----------------

    @Test
    void 文件原语_条目读写零变更跳过与日期扫描() throws Exception {
        service.ensurePersonalRepo("alice");
        var out = service.writeFile("alice", "entries/2026-10-12.md", "# x\n",
                "worklog: entries 2026-10-12", WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(out.committed());
        assertEquals("alice/entries/2026-10-12.md", out.relPath());
        assertEquals("# x\n", service.readFileIn("alice", "entries/2026-10-12.md").orElseThrow());
        // 内容一致 → 零 commit
        assertFalse(service.writeFile("alice", "entries/2026-10-12.md", "# x\n",
                "worklog: entries 2026-10-12", WorklogRepoService.CommitSource.MANUAL, null, null).committed());
        // GIT_IMPORT trailer 落 git log
        service.writeFile("alice", "entries/2026-10-11.md", "# y\n",
                "worklog: entries 2026-10-11 (git import)",
                WorklogRepoService.CommitSource.GIT_IMPORT, null, null);
        String log = runGit(aliceRepoDir(), "log", "--format=%B", "-1");
        assertTrue(log.contains("DevMind-Source: git-import"));
        // 日期扫描升序 + 范围过滤；无仓用户读路径不建仓
        assertEquals(List.of(LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-12")),
                service.listEntryDates("alice", null, null));
        assertEquals(List.of(LocalDate.parse("2026-10-12")),
                service.listEntryDates("alice", LocalDate.parse("2026-10-12"), null));
        assertTrue(service.listEntryDates("bob", null, null).isEmpty());
        assertTrue(service.readFileIn("bob", "entries/2026-10-12.md").isEmpty());
    }

    @Test
    void 文件原语_非法相对路径拒绝() {
        service.ensurePersonalRepo("alice");
        assertThrows(DevMindException.class, () -> service.writeFile("alice", "../x.md", "x",
                "s", WorklogRepoService.CommitSource.MANUAL, null, null));
        assertThrows(DevMindException.class, () -> service.writeFile("alice", "..\\x.md", "x",
                "s", WorklogRepoService.CommitSource.MANUAL, null, null));
        assertThrows(DevMindException.class, () -> service.readFileIn("alice", "/abs.md"));
    }
}
