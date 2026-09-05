package com.devmind.project;

import com.devmind.auth.IdentityService;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.config.ProjectProperties;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.dto.ProjectRequest;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.RepoRequest;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import com.devmind.project.repo.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-23/29 克隆模式创建单测（无 Spring 上下文）：
 * repository 用 JDK 动态代理内存 fake，IdentityService/DomainEventPublisher 子类覆盖，
 * GitRepoService 用真实实现 + fake 全局仓库表（CAP-29 起项目仓库只关联全局行）。
 * 覆盖：CLONE 创建路径=全局克隆目录与 gitrepo.clone-requested 事件、URL 校验（ssh/非法协议/file:// 带集成）、
 * CLONE 项目禁改 path、主库镜像同步、LOCAL 回归（path 必填校验）。
 */
class ProjectServiceCloneTest {

    @TempDir
    Path tempDir;

    private FakeProjectRepository projects;
    private FakeRepoRepository repos;
    private FakeGitRepoRepository gitRepos;
    private FakeEventPublisher events;
    private GitRepoService gitRepoService;
    private ProjectService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeIdentityService extends IdentityService {
        FakeIdentityService() { super(null, null, null); }
        @Override public String currentActor() { return "tester"; }
    }

    static class FakeEventPublisher extends DomainEventPublisher {
        final List<DomainEvent> published = new ArrayList<>();
        FakeEventPublisher() { super(null); }
        @Override public void publish(DomainEvent event) { published.add(event); }
    }

    static class FakeProjectRepository {
        final Map<String, ProjectEntity> store = new HashMap<>();

        ProjectRepository jpa() {
            return proxy(ProjectRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    ProjectEntity e = (ProjectEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                case "countByPath" -> store.values().stream()
                        .filter(e -> e.getPath().equals(args[0])).count();
                case "count" -> (long) store.size();
                case "findAll" -> new ArrayList<>(store.values());
                case "findAllByOrderByUpdatedAtDesc" -> store.values().stream()
                        .sorted(Comparator.comparing(ProjectEntity::getUpdatedAt).reversed()).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeRepoRepository {
        final Map<Long, ProjectRepoEntity> store = new HashMap<>();
        private long seq = 0;

        ProjectRepoRepository jpa() {
            return proxy(ProjectRepoRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    ProjectRepoEntity e = (ProjectRepoEntity) args[0];
                    if (e.getId() == null) {
                        e.setId(++seq);
                    }
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                case "countByProjectId" -> store.values().stream()
                        .filter(e -> e.getProjectId().equals(args[0])).count();
                case "countByProjectIdAndPath" -> store.values().stream()
                        .filter(e -> e.getProjectId().equals(args[0]) && e.getPath().equals(args[1])).count();
                case "findByProjectIdAndIsPrimaryTrue" -> store.values().stream()
                        .filter(e -> e.getProjectId().equals(args[0]) && Boolean.TRUE.equals(e.getIsPrimary()))
                        .findFirst();
                case "findByProjectIdOrderBySortOrderAscIdAsc" -> store.values().stream()
                        .filter(e -> e.getProjectId().equals(args[0]))
                        .sorted(Comparator.comparing(ProjectRepoEntity::getSortOrder)
                                .thenComparing(ProjectRepoEntity::getId)).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeGitRepoRepository {
        final Map<Long, com.devmind.project.model.GitRepositoryEntity> store = new HashMap<>();
        private long seq = 0;

        GitRepositoryRepository jpa() {
            return proxy(GitRepositoryRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    com.devmind.project.model.GitRepositoryEntity e =
                            (com.devmind.project.model.GitRepositoryEntity) args[0];
                    if (e.getId() == null) {
                        e.setId(++seq);
                    }
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                case "findByRemoteUrlKey" -> store.values().stream()
                        .filter(e -> java.util.Objects.equals(e.getRemoteUrlKey(), args[0])).findFirst();
                case "findByLocalPath" -> store.values().stream()
                        .filter(e -> e.getLocalPath().equals(args[0])).findFirst();
                case "findAll" -> new ArrayList<>(store.values());
                case "countByGitRepoId" -> 0L;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    @BeforeEach
    void setUp() {
        projects = new FakeProjectRepository();
        repos = new FakeRepoRepository();
        gitRepos = new FakeGitRepoRepository();
        events = new FakeEventPublisher();
        ProjectProperties props = new ProjectProperties();
        props.setWorkspaceRoot(tempDir.resolve("repositories").toString());
        FakeIdentityService identity = new FakeIdentityService();
        // CAP-29：真实 GitRepoService + fake 全局表（upsert/路径推导走真实逻辑）
        gitRepoService = new GitRepoService(gitRepos.jpa(), repos.jpa(), props, identity, events);
        service = new ProjectService(identity, props, new WorktreeProperties(),
                projects.jpa(), repos.jpa(), null, null, null, null,
                null, null, null, null, null, null, null, gitRepoService);
    }

    private ProjectRequest cloneRequest(String remoteUrl, Long integrationId) {
        return new ProjectRequest("demo", null, "CLONE", remoteUrl, integrationId,
                "master", List.of(), null, null, null, null, null);
    }

    @Test
    void createCloneProjectComputesWorkspacePathAndPublishesEvent() {
        ProjectView view = service.create(cloneRequest("https://gitlab.example.com/group/my-repo.git", null));

        // CAP-29：项目路径 = 全局克隆目录 <workspaceRoot>/_global/<slug>-<sha8>
        String expected = gitRepoService
                .deriveClonePath("https://gitlab.example.com/group/my-repo.git").toString();
        assertEquals(expected, view.path());
        assertEquals("CLONE", view.sourceType());
        assertEquals("CLONING", view.cloneStatus());

        ProjectRepoEntity primary = repos.store.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsPrimary())).findFirst().orElseThrow();
        assertEquals("my-repo", primary.getName());
        assertEquals("CLONE", primary.getSourceType());
        assertEquals("CLONING", primary.getCloneStatus());
        assertEquals("https://gitlab.example.com/group/my-repo.git", primary.getRemoteUrl());
        assertNull(primary.getIntegrationId());
        // 主库行已关联全局仓库行
        assertEquals(1, gitRepos.store.size());
        assertEquals(gitRepos.store.values().iterator().next().getId(), primary.getGitRepoId());

        // CAP-29：事件改由 GitRepoService 发布（gitrepo.clone-requested / GIT_REPO）
        assertEquals(1, events.published.size());
        var event = (com.devmind.common.event.SimpleDomainEvent) events.published.get(0);
        assertEquals("gitrepo.clone-requested", event.type());
        assertEquals("GIT_REPO", event.entityType());
        assertEquals(String.valueOf(primary.getGitRepoId()), event.entityId());
    }

    @Test
    void createCloneProjectRejectsInvalidRemote() {
        var ssh = assertThrows(DevMindException.class,
                () -> service.create(cloneRequest("git@gitlab.example.com:group/repo.git", null)));
        assertEquals(ErrorCode.BAD_REQUEST, ssh.getErrorCode());

        assertThrows(DevMindException.class,
                () -> service.create(cloneRequest("ftp://x/repo.git", null)));
        assertThrows(DevMindException.class,
                () -> service.create(cloneRequest("https://", null)));
        // file:// 带集成实例 → 拒绝
        assertThrows(DevMindException.class,
                () -> service.create(cloneRequest("file:///tmp/origin.git", 7L)));
        assertTrue(events.published.isEmpty());
        assertTrue(projects.store.isEmpty());
    }

    @Test
    void createCloneProjectAcceptsFileUrlWhenAnonymous() {
        ProjectView view = service.create(cloneRequest("file:///tmp/origin.git", null));
        assertEquals("CLONING", view.cloneStatus());
    }

    @Test
    void cloneProjectRejectsPathUpdate() {
        ProjectView view = service.create(cloneRequest("https://gitlab.example.com/g/r.git", null));
        var ex = assertThrows(DevMindException.class, () -> service.update(view.id(),
                new ProjectRequest("demo", "D:/somewhere", null, null, null,
                        null, null, null, null, null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void addCloneRepoLinksGlobalRowAndDeduplicatesByUrl() {
        ProjectView project = service.create(cloneRequest("https://gitlab.example.com/g/main.git", null));
        RepoView r1 = service.addRepo(project.id(),
                new RepoRequest("docs repo", null, "CLONE", "https://gitlab.example.com/g/docs.git",
                        null, null, null, false, 0));
        // CAP-29：path = 全局克隆目录（按 URL 推导，不再按项目分目录）
        assertEquals(gitRepoService.deriveClonePath("https://gitlab.example.com/g/docs.git").toString(),
                r1.path());
        assertEquals("CLONING", r1.cloneStatus());

        RepoView r2 = service.addRepo(project.id(),
                new RepoRequest("docs repo", null, "CLONE", "https://gitlab.example.com/g/docs2.git",
                        null, null, null, false, 1));
        assertEquals(gitRepoService.deriveClonePath("https://gitlab.example.com/g/docs2.git").toString(),
                r2.path());
        // create + 两次 addRepo 各 upsert 一行全局仓库
        assertEquals(3, gitRepos.store.size());
        assertEquals(3, events.published.size());

        // 同一远端重复添加 → 关联同一全局行 → 路径冲突拒绝
        var dup = assertThrows(DevMindException.class, () -> service.addRepo(project.id(),
                new RepoRequest("docs again", null, "CLONE", "https://gitlab.example.com/g/docs.git",
                        null, null, null, false, 2)));
        assertEquals(ErrorCode.CONFLICT, dup.getErrorCode());
        assertEquals(3, gitRepos.store.size());
    }

    @Test
    void syncPrimaryMirrorPropagatesCloneStatus() {
        ProjectView project = service.create(cloneRequest("https://gitlab.example.com/g/r.git", null));
        ProjectRepoEntity primary = repos.store.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsPrimary())).findFirst().orElseThrow();
        primary.setCloneStatus(ProjectRepoEntity.CLONE_READY);
        repos.store.put(primary.getId(), primary);

        service.syncPrimaryMirror(project.id());
        assertEquals("READY", projects.store.get(project.id()).getCloneStatus());
    }

    @Test
    void localProjectStillRequiresGitRepoPath() {
        var ex = assertThrows(DevMindException.class, () -> service.create(
                new ProjectRequest("local-demo", tempDir.resolve("not-a-repo").toString(), null, null, null,
                        null, null, null, null, null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(events.published.isEmpty());
    }

    @Test
    void updateCloneProjectRemoteUrlMirrorsToPrimaryRepo() {
        ProjectView project = service.create(cloneRequest("https://gitlab.example.com/g/r.git", null));
        service.update(project.id(), new ProjectRequest("demo", null, null,
                "https://gitlab.example.com/g/r2.git", 9L, null, null, null, null, null, null, null));
        ProjectRepoEntity primary = repos.store.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsPrimary())).findFirst().orElseThrow();
        assertEquals("https://gitlab.example.com/g/r2.git", primary.getRemoteUrl());
        assertEquals(9L, primary.getIntegrationId());
        assertNotNull(primary);
    }
}
