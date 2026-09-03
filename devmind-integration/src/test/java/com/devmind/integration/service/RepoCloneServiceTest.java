package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-23 RepoCloneService 单测（无 Spring 上下文，真实 git + file:// 匿名通道）：
 * repository 用 JDK 动态代理内存 fake，ProjectService/IntegrationService 子类覆盖。
 * 覆盖：非 CLONE 库拒绝、CLONING 并发拒绝、匿名克隆全链路 READY + 默认分支探测 +
 * origin URL 无 token、集成实例缺失/类型不符 → FAILED。
 */
class RepoCloneServiceTest {

    @TempDir
    Path tempDir;

    private FakeRepoRepository repos;
    private FakeIntegrationRepository integrations;
    private RepoCloneService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeRepoRepository {
        final Map<Long, ProjectRepoEntity> store = new HashMap<>();
        private long seq = 0;

        ProjectRepoEntity add(ProjectRepoEntity e) {
            e.setId(++seq);
            store.put(e.getId(), e);
            return e;
        }

        ProjectRepoRepository jpa() {
            return proxy(ProjectRepoRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    ProjectRepoEntity e = (ProjectRepoEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                case "findByProjectIdOrderBySortOrderAscIdAsc" -> store.values().stream()
                        .filter(e -> e.getProjectId().equals(args[0])).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeIntegrationRepository {
        final Map<Long, IntegrationEntity> store = new HashMap<>();

        IntegrationRepository jpa() {
            return proxy(IntegrationRepository.class, (p, m, args) -> switch (m.getName()) {
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeProjectService extends ProjectService {
        int mirrorCalls = 0;

        FakeProjectService() {
            super(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }

        @Override public void syncPrimaryMirror(String projectId) { mirrorCalls++; }
    }

    static class FakeIntegrationService extends IntegrationService {
        FakeIntegrationService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    java.util.List.of());
        }

        @Override public String tokenOf(IntegrationEntity e) { return "fake-token"; }
    }

    @BeforeEach
    void setUp() {
        repos = new FakeRepoRepository();
        integrations = new FakeIntegrationRepository();
        service = new RepoCloneService(repos.jpa(), new FakeProjectService(), integrations.jpa(),
                new FakeIntegrationService(), new GitRemoteOps(), new ExecutionLogHub(new ObjectMapper()));
    }

    private ProjectRepoEntity cloneRow(String remoteUrl, Long integrationId) {
        ProjectRepoEntity r = new ProjectRepoEntity();
        r.setProjectId("proj1");
        r.setName("repo-" + remoteUrl.hashCode());
        r.setPath(tempDir.resolve("target-" + System.nanoTime()).toString());
        r.setRemoteUrl(remoteUrl);
        r.setSourceType(ProjectRepoEntity.SOURCE_CLONE);
        r.setIntegrationId(integrationId);
        r.setCloneStatus(ProjectRepoEntity.CLONE_CLONING);
        r.setIsPrimary(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return repos.add(r);
    }

    /** 造一个带一次提交的裸仓库，返回 file:// URL。 */
    private String seedBareRepo() throws Exception {
        Path work = tempDir.resolve("seed-" + System.nanoTime());
        runGit(null, "git", "init", "-b", "main", work.toString());
        Files.writeString(work.resolve("README.md"), "hi");
        runGit(work, "git", "-C", work.toString(), "add", ".");
        runGit(work, "git", "-C", work.toString(),
                "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        Path bare = tempDir.resolve("origin-" + System.nanoTime() + ".git");
        runGit(null, "git", "clone", "--bare", work.toString(), bare.toString());
        return bare.toUri().toString();
    }

    private void runGit(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git 命令失败: " + String.join(" ", cmd));
    }

    /** 等待异步克隆真正完成（终态 + clonedAt/cloneError 已写，避免初始状态被误判为终态）。 */
    private ProjectRepoEntity awaitDone(Long repoId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ProjectRepoEntity r = repos.store.get(repoId);
            boolean written = r.getClonedAt() != null || r.getCloneError() != null;
            if (written && !ProjectRepoEntity.CLONE_CLONING.equals(r.getCloneStatus())) {
                return r;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("克隆未在超时内完成");
    }

    @Test
    void rejectsLocalRepoAndConcurrentTrigger() {
        ProjectRepoEntity local = cloneRow("file:///x", null);
        local.setSourceType(ProjectRepoEntity.SOURCE_LOCAL);
        local.setCloneStatus(ProjectRepoEntity.CLONE_NONE);
        var ex = assertThrows(DevMindException.class, () -> service.requestClone("proj1", local.getId()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        ProjectRepoEntity busy = cloneRow("file:///y", null);
        service.inFlight.add(busy.getId()); // 模拟在途克隆
        var conflict = assertThrows(DevMindException.class,
                () -> service.requestClone("proj1", busy.getId()));
        assertEquals(ErrorCode.CONFLICT, conflict.getErrorCode());
    }

    @Test
    void anonymousFileCloneReachesReadyAndDetectsDefaultBranch() throws Exception {
        String url = seedBareRepo();
        ProjectRepoEntity repo = cloneRow(url, null); // 创建即 CLONING（事件路径同款初态）

        RepoView view = service.requestClone("proj1", repo.getId());
        assertNotNull(view);

        ProjectRepoEntity done = awaitDone(repo.getId());
        assertEquals(ProjectRepoEntity.CLONE_READY, done.getCloneStatus());
        assertEquals("main", done.getDefaultBranch());
        assertNotNull(done.getClonedAt());
        assertTrue(done.getCloneLogs().contains("git clone"), "日志应含克隆命令");
        assertTrue(Files.isDirectory(Path.of(done.getPath()).resolve(".git")), "目标应为 git 仓库");
        String gitConfig = Files.readString(Path.of(done.getPath()).resolve(".git").resolve("config"));
        assertFalse(gitConfig.contains("fake-token"), ".git/config 不得残留 token");
    }

    @Test
    void missingIntegrationFailsClone() throws Exception {
        // https 地址走到集成校验（克隆不会真正发起，token 解析先失败）
        ProjectRepoEntity repo = cloneRow("https://gitlab.example.com/g/r.git", 999L);
        service.requestClone("proj1", repo.getId());

        ProjectRepoEntity done = awaitDone(repo.getId());
        assertEquals(ProjectRepoEntity.CLONE_FAILED, done.getCloneStatus());
        assertTrue(done.getCloneError().contains("集成实例不存在"));
    }

    @Test
    void jiraIntegrationRejectedForClone() throws Exception {
        IntegrationEntity jira = new IntegrationEntity();
        jira.setId(5L);
        jira.setName("jira");
        jira.setType(IntegrationEntity.TYPE_JIRA);
        jira.setStatus(IntegrationEntity.STATUS_ENABLED);
        jira.setBaseUrl("https://jira.example.com");
        integrations.store.put(5L, jira);

        ProjectRepoEntity repo = cloneRow("https://gitlab.example.com/g/r.git", 5L);
        service.requestClone("proj1", repo.getId());

        ProjectRepoEntity done = awaitDone(repo.getId());
        assertEquals(ProjectRepoEntity.CLONE_FAILED, done.getCloneStatus());
        assertTrue(done.getCloneError().contains("仅支持 GitLab/GitHub"));
    }

    @Test
    void cloneLogsReturnsAccumulatedText() throws Exception {
        String url = seedBareRepo();
        ProjectRepoEntity repo = cloneRow(url, null);
        service.requestClone("proj1", repo.getId());
        awaitDone(repo.getId());
        assertTrue(service.cloneLogs("proj1", repo.getId()).contains("git clone"));
    }
}
