package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.SettingsRequest;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-74 M2（C6）：团队仓成员管理 + active_repo_key 切换校验 + 读路径 TTL 后台 fetch。
 * 仓与远端全真 git（@TempDir + file:// bare），repo/member/settings 表内存 stub。
 */
class WorklogTeamRepoTest {

    @TempDir
    Path tmp;

    private final GitRemoteOps git = new GitRemoteOps(nullEgressProvider());

    private WorklogRepoService repoService;
    private WorklogUserSettingsRepository settingsRepository;
    private final Map<String, WorklogRepoEntity> repoRows = new HashMap<>();
    private final List<WorklogRepoMemberEntity> memberRows = new ArrayList<>();
    private final Map<String, WorklogUserSettingsEntity> settingsRows = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(tmp.resolve("repos").toString());

        WorklogRepoRepository repoRepository = mock(WorklogRepoRepository.class);
        when(repoRepository.findByRepoKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(repoRows.get(inv.getArgument(0))));
        when(repoRepository.findById(anyLong()))
                .thenAnswer(inv -> repoRows.values().stream()
                        .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repoRepository.save(any(WorklogRepoEntity.class))).thenAnswer(inv -> {
            WorklogRepoEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.getAndIncrement());
            }
            repoRows.put(e.getRepoKey(), e);
            return e;
        });

        WorklogRepoMemberRepository memberRepository = mock(WorklogRepoMemberRepository.class);
        when(memberRepository.save(any(WorklogRepoMemberEntity.class))).thenAnswer(inv -> {
            WorklogRepoMemberEntity m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(ids.getAndIncrement());
            }
            memberRows.add(m);
            return m;
        });
        when(memberRepository.findByRepoIdAndUserId(anyLong(), anyString())).thenAnswer(inv ->
                memberRows.stream()
                        .filter(m -> m.getRepoId().equals(inv.getArgument(0))
                                && m.getUserId().equals(inv.getArgument(1)))
                        .findFirst());
        when(memberRepository.findByRepoId(anyLong())).thenAnswer(inv ->
                memberRows.stream().filter(m -> m.getRepoId().equals(inv.getArgument(0))).toList());
        when(memberRepository.findByUserId(anyString())).thenAnswer(inv ->
                memberRows.stream().filter(m -> m.getUserId().equals(inv.getArgument(0))).toList());
        org.mockito.Mockito.doAnswer(inv -> {
            memberRows.remove(inv.getArgument(0));
            return null;
        }).when(memberRepository).delete(any(WorklogRepoMemberEntity.class));

        settingsRepository = mock(WorklogUserSettingsRepository.class);
        when(settingsRepository.findByUserId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(settingsRows.get(inv.getArgument(0))));
        when(settingsRepository.save(any(WorklogUserSettingsEntity.class))).thenAnswer(inv -> {
            WorklogUserSettingsEntity e = inv.getArgument(0);
            settingsRows.put(e.getUserId(), e);
            return e;
        });

        repoService = new WorklogRepoService(props, repoRepository, memberRepository,
                settingsRepository, git, nullIdentityProvider(), nullGatewayProvider(), nullGatewayProvider());
    }

    // ---------------- 成员管理 ----------------

    @Test
    void ownerAddsMemberWithSkeletonCommit() {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        repoService.addMember(repo.getRepoKey(), "alice", "bob");

        assertTrue(repoService.isMember(repo, "bob"));
        Path dir = repoService.repoDir(repo.getRepoKey());
        assertTrue(Files.exists(dir.resolve("bob/daily/.gitkeep")));
        assertTrue(Files.exists(dir.resolve("bob/weekly/.gitkeep")));
        // 骨架子目录已进 git 历史
        GitRemoteOps.GitResult log = git.headCommitAt(dir.toString());
        assertTrue(log.ok());
        // bob 视角能看到该仓（设置页切换数据源）
        assertEquals(1, repoService.listMyRepos("bob").size());
        assertEquals(repo.getRepoKey(), repoService.listMyRepos("bob").get(0).repoKey());
        assertFalse(repoService.listMyRepos("bob").get(0).personal());
        // 成员列表带 owner 标记
        var members = repoService.listMembers(repo.getRepoKey(), "bob");
        assertEquals(2, members.size());
        assertTrue(members.stream().filter(m -> m.owner()).anyMatch(m -> m.username().equals("alice")));
    }

    @Test
    void nonOwnerCannotManageMembers() {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        repoService.addMember(repo.getRepoKey(), "alice", "bob");
        DevMindException e = assertThrows(DevMindException.class,
                () -> repoService.addMember(repo.getRepoKey(), "bob", "carol"));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        e = assertThrows(DevMindException.class,
                () -> repoService.removeMember(repo.getRepoKey(), "bob", "alice"));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
    }

    @Test
    void duplicateMemberRejected409() {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        repoService.addMember(repo.getRepoKey(), "alice", "bob");
        DevMindException e = assertThrows(DevMindException.class,
                () -> repoService.addMember(repo.getRepoKey(), "alice", "bob"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void removeMemberKeepsFilesAndRejectsOwnerRemoval() {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        repoService.addMember(repo.getRepoKey(), "alice", "bob");

        DevMindException e = assertThrows(DevMindException.class,
                () -> repoService.removeMember(repo.getRepoKey(), "alice", "alice"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());

        repoService.removeMember(repo.getRepoKey(), "alice", "bob");
        assertFalse(repoService.isMember(repo, "bob"));
        // 仓内骨架子目录保留（历史文件不删）
        assertTrue(Files.exists(repoService.repoDir(repo.getRepoKey()).resolve("bob/daily/.gitkeep")));

        e = assertThrows(DevMindException.class,
                () -> repoService.removeMember(repo.getRepoKey(), "alice", "bob"));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    // ---------------- active_repo_key 切换 ----------------

    @Test
    void activeRepoKeySwitchValidation() {
        WorklogRepoEntity aliceRepo = repoService.ensurePersonalRepo("alice");
        WorklogRepoEntity bobRepo = repoService.ensurePersonalRepo("bob");
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn("alice");
        WorklogSettingsService settings = new WorklogSettingsService(
                settingsRepository, identity, new WorklogProperties(), repoService);

        // 非成员仓 → 400
        SettingsRequest toBobs = settingsRequestWithActiveRepo(bobRepo.getRepoKey());
        DevMindException e = assertThrows(DevMindException.class, () -> settings.update(toBobs));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());

        // 加入后切换成功
        repoService.addMember(bobRepo.getRepoKey(), "bob", "alice");
        var view = settings.update(toBobs);
        assertEquals(bobRepo.getRepoKey(), view.activeRepoKey());
        // alice 的活动仓路由到 bob 的仓
        assertEquals(bobRepo.getRepoKey(), repoService.activeRepoFor("alice").getRepoKey());

        // 空白串 → 回个人仓
        var cleared = settings.update(settingsRequestWithActiveRepo("  "));
        assertEquals(null, cleared.activeRepoKey());
        assertEquals(aliceRepo.getRepoKey(), repoService.activeRepoFor("alice").getRepoKey());

        // 不存在的仓 → 400
        e = assertThrows(DevMindException.class,
                () -> settings.update(settingsRequestWithActiveRepo("ghost-repo")));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    private static SettingsRequest settingsRequestWithActiveRepo(String key) {
        return new SettingsRequest(null, null, null, null, null, null, null, null, key,
                null, null, null, null);
    }

    // ---------------- TTL fetch ----------------

    @Test
    void readPathTriggersBackgroundFetchAndMergesRemoteChanges() throws Exception {
        // alice 仓绑 file:// 远端并推一版
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        String url = bareRemote("ttl-origin");
        repo.setRemoteUrl(url);
        repoService.saveRepo(repo);
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "v1\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(git.pushBranch(repoService.repoDir(repo.getRepoKey()).toString(),
                "main", url, null).ok());

        // 平台外 clone 改文件推回（远端领先）
        Path external = tmp.resolve("ttl-external");
        assertTrue(git.cloneRepo(url, null, external.toString(), "main", null).ok());
        Files.writeString(external.resolve("alice/daily/2026-10-12.md"), "远端 v2\n",
                StandardCharsets.UTF_8);
        runGit(external, "-c", "user.name=ext", "-c", "user.email=ext@x.com",
                "commit", "-am", "external edit");
        runGit(external, "push", url, "main:main");

        // 读路径触发后台 fetch+merge：等虚拟线程落定后读到新内容
        repoService.findActiveRepo("alice");
        awaitTrue(() -> {
            var c = repoService.readReport("alice", WorklogRepoService.ReportKind.DAILY,
                    LocalDate.of(2026, 10, 12));
            return c.isPresent() && c.get().contentMd().contains("远端 v2");
        }, 10_000);
        assertFalse(repoService.isFetchStale(repo.getRepoKey()));
    }

    @Test
    void backgroundFetchFailureIsFailVisibleNotBlocking() throws Exception {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        String url = bareRemote("ttl-origin2");
        repo.setRemoteUrl(url);
        repoService.saveRepo(repo);
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "v1\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);

        // 远端目录删掉 → fetch 必失败；读不阻塞、stale 置位
        deleteRecursively(tmp.resolve("ttl-origin2.git"));
        repoService.overrideFetchTtlForTest(0);
        repoService.findActiveRepo("alice");
        awaitTrue(() -> repoService.isFetchStale(repo.getRepoKey()), 10_000);
        // stale 落定即首次 fetch 的 git 子进程已退；封死 TTL 防后续读路径再起 fetch 与 @TempDir 清理抢目录
        repoService.overrideFetchTtlForTest(Long.MAX_VALUE);
        // 读路径仍正常返回本地内容
        var c = repoService.readReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12));
        assertTrue(c.isPresent());
        assertEquals("v1\n", c.get().contentMd().replace("\r\n", "\n"));
    }

    // ---------------- 工装 ----------------

    private String bareRemote(String name) throws Exception {
        Path origin = tmp.resolve(name + ".git");
        runGit(tmp, "init", "--bare", "-b", "main", origin.toString());
        return "file://" + origin.toString().replace('\\', '/');
    }

    private static void runGit(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
    }

    private static void awaitTrue(Check c, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.check()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待超时（" + timeoutMs + "ms）条件未满足");
    }

    @FunctionalInterface
    private interface Check {
        boolean check() throws Exception;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static ObjectProvider<EgressProxyRouter> nullEgressProvider() {
        return new ObjectProvider<>() {
            public EgressProxyRouter getObject() { return null; }
            public EgressProxyRouter getObject(Object... args) { return null; }
            public EgressProxyRouter getIfAvailable() { return null; }
            public EgressProxyRouter getIfUnique() { return null; }
        };
    }

    private static ObjectProvider<GitIdentityProvider> nullIdentityProvider() {
        return new ObjectProvider<>() {
            public GitIdentityProvider getObject() { return null; }
            public GitIdentityProvider getObject(Object... args) { return null; }
            public GitIdentityProvider getIfAvailable() { return null; }
            public GitIdentityProvider getIfUnique() { return null; }
        };
    }

    private static <T> ObjectProvider<T> nullGatewayProvider() {
        return new ObjectProvider<>() {
            public T getObject() { return null; }
            public T getObject(Object... args) { return null; }
            public T getIfAvailable() { return null; }
            public T getIfUnique() { return null; }
        };
    }
}
