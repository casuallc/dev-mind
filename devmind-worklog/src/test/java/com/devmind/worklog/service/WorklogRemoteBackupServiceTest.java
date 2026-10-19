package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.model.WorklogRepoMemberEntity;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-74 {@link WorklogRemoteBackupService}：服务端 push 全链真 git（@TempDir + file:// bare 远端）。
 * 覆盖：未绑定 400 / https 无 PAT 409 / 首次直推内容与远端一致 / 分叉冲突 409+abort 不留 MERGING /
 * resolve ours|theirs / 远端分支不同名 refspec / 非成员 resolve 403。
 * repo/member/settings 表用内存 stub（mock 答案挂 HashMap），仓与远端全是真 git。
 */
class WorklogRemoteBackupServiceTest {

    @TempDir
    Path tmp;

    private final GitRemoteOps git = new GitRemoteOps(nullEgressProvider());

    private WorklogRepoService repoService;
    private WorklogRemoteBackupService service;
    private RepoGitGateway gateway;
    private DomainEventPublisher eventPublisher;
    /** 自动推送开关（null=关闭）：只在自动推送专项测试里挂上 service，存量测试不受异步推送干扰 */
    private volatile WorklogRemoteBackupService autoPushBean;
    private final Map<String, WorklogRepoEntity> repoRows = new HashMap<>();
    private final List<WorklogRepoMemberEntity> memberRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(tmp.resolve("repos").toString());

        WorklogRepoRepository repoRepository = mock(WorklogRepoRepository.class);
        when(repoRepository.findByRepoKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(repoRows.get(inv.getArgument(0))));
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

        WorklogUserSettingsRepository settingsRepository = mock(WorklogUserSettingsRepository.class);
        when(settingsRepository.findByUserId(anyString())).thenReturn(Optional.empty());

        repoService = new WorklogRepoService(props, repoRepository, memberRepository,
                settingsRepository, git, nullIdentityProvider(), mockProvider(null), autoPushProvider());

        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn("alice");
        gateway = mock(RepoGitGateway.class);
        eventPublisher = mock(DomainEventPublisher.class);
        service = new WorklogRemoteBackupService(identity, repoService, git, mockProvider(gateway),
                eventPublisher);
    }

    // ---------------- 前置校验 ----------------

    @Test
    void unboundRemoteRejected() {
        repoService.ensurePersonalRepo("alice");
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("未绑定远程仓库"), e.getMessage());
    }

    @Test
    void httpsWithoutPersonalPatRejected() {
        bindRemote("https://git.example.com/u/w.git", null);
        when(gateway.resolveToken(eq("alice"), eq("git.example.com"), any()))
                .thenReturn(Optional.empty());
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("个人访问令牌"), e.getMessage());
        assertTrue(e.getMessage().contains("git.example.com"), e.getMessage());
    }

    // ---------------- push 主链 ----------------

    @Test
    void firstPushDirectAndContentMatchesRemote() throws Exception {
        String url = bareRemote("origin1");
        bindRemote(url, null);
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "# 日报\n平台内容\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);

        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok(), r.error());

        // 克隆远端验证内容字节级一致
        Path clone = tmp.resolve("verify1");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("# 日报\n平台内容\n",
                readNorm(clone.resolve("alice/daily/2026-10-12.md")));
    }

    @Test
    void secondPushFastForward() throws Exception {
        String url = bareRemote("origin2");
        bindRemote(url, null);
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "v1\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(service.pushToRemote().ok());
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 13), "v2\n",
                WorklogRepoService.CommitSource.AI, "s1", null);
        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok(), r.error());
    }

    @Test
    void divergedConflict409AndAbortClearsMerging() throws Exception {
        String url = makeConflict("origin3");
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("alice/daily/2026-10-12.md"), e.getMessage());
        // abort 已清 MERGING，仓不留半合并状态；本地工作区仍是平台版
        assertFalse(Files.exists(repoDir().resolve(".git/MERGE_HEAD")));
        assertEquals("平台版 v2\n", readNorm(repoDir().resolve("alice/daily/2026-10-12.md")));
        // 冲突未推送：远端仍是远端版
        Path clone = tmp.resolve("verify-conflict");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("远端版\n", readNorm(clone.resolve("alice/daily/2026-10-12.md")));
    }

    @Test
    void resolveConflictOursKeepsPlatformContent() throws Exception {
        String url = makeConflict("origin4");
        assertThrows(DevMindException.class, () -> service.pushToRemote());

        WorklogPushResult r = service.resolveConflict(
                WorklogRepoService.personalRepoKey("alice"), "ours");
        assertTrue(r.ok(), r.error());

        Path clone = tmp.resolve("verify-ours");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("平台版 v2\n", readNorm(clone.resolve("alice/daily/2026-10-12.md")));
        assertFalse(Files.exists(repoDir().resolve(".git/MERGE_HEAD")));
    }

    @Test
    void resolveConflictTheirsTakesRemoteContent() throws Exception {
        String url = makeConflict("origin5");
        assertThrows(DevMindException.class, () -> service.pushToRemote());

        WorklogPushResult r = service.resolveConflict(
                WorklogRepoService.personalRepoKey("alice"), "theirs");
        assertTrue(r.ok(), r.error());

        Path clone = tmp.resolve("verify-theirs");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("远端版\n", readNorm(clone.resolve("alice/daily/2026-10-12.md")));
    }

    @Test
    void resolveConflictRejectsBadStrategy() throws Exception {
        makeConflict("origin6");
        assertThrows(DevMindException.class, () -> service.pushToRemote());
        DevMindException e = assertThrows(DevMindException.class, () -> service.resolveConflict(
                WorklogRepoService.personalRepoKey("alice"), "theirs-plus"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void resolveConflictForbiddenForNonMember() throws Exception {
        String url = bareRemote("origin7");
        bindRemote(url, null);
        memberRows.clear(); // alice 不再是成员
        DevMindException e = assertThrows(DevMindException.class, () -> service.resolveConflict(
                WorklogRepoService.personalRepoKey("alice"), "ours"));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
    }

    @Test
    void resolveConflictRepoNotFound404() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.resolveConflict("personal-ghost", "ours"));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    void pushToDifferentRemoteBranchViaRefspec() throws Exception {
        String url = bareRemote("origin8");
        bindRemote(url, "backup");
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "x\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);

        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok(), r.error());
        // 远端 backup 分支有内容、main 分支不存在
        assertFalse(git.lsRemote(url, null, "refs/heads/backup").output().isBlank());
        assertTrue(git.lsRemote(url, null, "refs/heads/main").output().isBlank());
    }

    // ---------------- 写后自动推送 ----------------

    @Test
    void autoPushAfterWriteReachesRemote() throws Exception {
        String url = bareRemote("auto1");
        bindRemote(url, null);
        autoPushBean = service;

        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "# 自动推送\n",
                WorklogRepoService.CommitSource.AI, "s1", null);

        // 异步推送：等远端出现 main 分支再克隆校验内容
        awaitTrue(() -> !git.lsRemote(url, null, "refs/heads/main").output().isBlank());
        Path clone = tmp.resolve("verify-auto1");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("# 自动推送\n", readNorm(clone.resolve("alice/daily/2026-10-12.md")));
        assertFalse(repoService.isFetchStale(WorklogRepoService.personalRepoKey("alice")));
    }

    @Test
    void autoPushConflictMarksStaleAndNotifiesOwner() throws Exception {
        String url = bareRemote("auto2");
        bindRemote(url, null);
        // 共同祖先先手动推上去（自动推送尚未开启，不干扰布景）
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "共同祖先\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(service.pushToRemote().ok());

        // 远端侧：外部 clone 改同一文件并推回
        Path external = tmp.resolve("auto2-external");
        assertTrue(git.cloneRepo(url, null, external.toString(), "main", null).ok());
        Files.writeString(external.resolve("alice/daily/2026-10-12.md"), "远端版\n", StandardCharsets.UTF_8);
        runGit(external, "-c", "user.name=ext", "-c", "user.email=ext@x.com",
                "commit", "-am", "external edit");
        runGit(external, "push", url, "main:main");

        // 本地写触发自动推送 → 分叉冲突：置同步异常位 + P0 通知，不自动选边
        autoPushBean = service;
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "平台版 v2\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);

        String key = WorklogRepoService.personalRepoKey("alice");
        awaitTrue(() -> repoService.isFetchStale(key));
        verify(eventPublisher, timeout(10_000).atLeastOnce()).publish(any(SimpleDomainEvent.class));
        assertFalse(Files.exists(repoDir().resolve(".git/MERGE_HEAD")));

        // 冲突未推送：远端仍是远端版
        Path clone = tmp.resolve("verify-auto2");
        assertTrue(git.cloneRepo(url, null, clone.toString(), "main", null).ok());
        assertEquals("远端版\n", readNorm(clone.resolve("alice/daily/2026-10-12.md")));

        // 手动 resolve 后推送成功，同步异常位复位
        autoPushBean = null;
        assertTrue(service.resolveConflict(key, "ours").ok());
        assertFalse(repoService.isFetchStale(key));
    }

    @Test
    void writeWithoutRemoteSkipsAutoPush() {
        autoPushBean = service;
        WorklogRepoService.WriteOutcome out = repoService.writeReport("alice",
                WorklogRepoService.ReportKind.DAILY, LocalDate.of(2026, 10, 12), "x\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(out.committed());
        assertFalse(repoService.isFetchStale(WorklogRepoService.personalRepoKey("alice")));
    }

    /** 异步断言：200ms 步长轮询，20s 超时。 */
    private static void awaitTrue(Check check) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("异步等待超时（20s）");
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    // ---------------- 工装 ----------------

    /** 制造冲突：本地与远端双侧改同一文件后的分叉状态（尚未 push），返回远端 URL。 */
    private String makeConflict(String originName) throws Exception {
        String url = bareRemote(originName);
        bindRemote(url, null);
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "共同祖先\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        assertTrue(service.pushToRemote().ok());

        // 远端侧：外部 clone 改同一文件并推回
        Path external = tmp.resolve(originName + "-external");
        assertTrue(git.cloneRepo(url, null, external.toString(), "main", null).ok());
        Path extFile = external.resolve("alice/daily/2026-10-12.md");
        Files.writeString(extFile, "远端版\n", StandardCharsets.UTF_8);
        runGit(external, "-c", "user.name=ext", "-c", "user.email=ext@x.com",
                "commit", "-am", "external edit");
        runGit(external, "push", url, "main:main");

        // 本地侧：改同一文件（不 push），形成分叉
        repoService.writeReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 12), "平台版 v2\n",
                WorklogRepoService.CommitSource.MANUAL, null, null);
        return url;
    }

    /** 建 bare 远端并返回 file:// URL。 */
    private String bareRemote(String name) throws Exception {
        Path origin = tmp.resolve(name + ".git");
        runGit(tmp, "init", "--bare", "-b", "main", origin.toString());
        return "file://" + origin.toString().replace('\\', '/');
    }

    /** 建仓并绑定远端（保存回内存表）。 */
    private WorklogRepoEntity bindRemote(String url, String branch) {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo("alice");
        repo.setRemoteUrl(url);
        if (branch != null) {
            repo.setRemoteBranch(branch);
        }
        return repoService.saveRepo(repo);
    }

    private Path repoDir() {
        return repoService.repoDir(WorklogRepoService.personalRepoKey("alice"));
    }

    /** Windows autocrlf 会把工作区检出成 CRLF，断言统一归一化换行。 */
    private static String readNorm(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    /** 自动推送 bean 提供者：读 autoPushBean 字段（null=关闭，专项测试里挂 service 开启）。 */
    private ObjectProvider<WorklogRemoteBackupService> autoPushProvider() {
        return new ObjectProvider<>() {
            public WorklogRemoteBackupService getObject() { return autoPushBean; }
            public WorklogRemoteBackupService getObject(Object... args) { return autoPushBean; }
            public WorklogRemoteBackupService getIfAvailable() { return autoPushBean; }
            public WorklogRemoteBackupService getIfUnique() { return autoPushBean; }
        };
    }
}
