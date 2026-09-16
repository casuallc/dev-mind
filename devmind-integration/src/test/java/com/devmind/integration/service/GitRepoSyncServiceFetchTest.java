package com.devmind.integration.service;

import com.devmind.project.ProjectService;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GitRepoSyncService.fetchOne 默认分支回归：用户手工指定的默认分支（default_branch_manual=true）
 * 不得被 fetch 的远端 HEAD 探测回写；未手工指定时保持既有「HEAD 漂移自动跟踪 + 镜像关联行」。
 * 无 Spring 上下文：repository 用 JDK 动态代理内存 fake，GitRemoteOps/CloneTokenResolver 子类覆盖。
 */
class GitRepoSyncServiceFetchTest {

    private FakeGitRepoRepository gitRepos;
    private FakeProjectRepoRepository projectRepos;
    private List<String> mirroredProjectIds;
    private GitRepoSyncService service;
    private StubGitRemoteOps gitOps;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeGitRepoRepository {
        final Map<Long, GitRepositoryEntity> store = new HashMap<>();
        private long seq = 0;

        GitRepositoryEntity add(GitRepositoryEntity e) {
            e.setId(++seq);
            store.put(e.getId(), e);
            return e;
        }

        GitRepositoryRepository jpa() {
            return proxy(GitRepositoryRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    GitRepositoryEntity e = (GitRepositoryEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeProjectRepoRepository {
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
                case "findByGitRepoId" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getGitRepoId())).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    /** 远端 HEAD 固定返回 main；fetch/分支列表/ff 全成功。 */
    static class StubGitRemoteOps extends GitRemoteOps {
        @Override
        public GitResult fetchAllRefs(String repoPath, String remoteUrl, String token) {
            return new GitResult(true, "");
        }

        @Override
        public GitResult listRemoteBranches(String repoPath) {
            return new GitResult(true, "origin/main\norigin/dev\norigin/HEAD");
        }

        @Override
        public GitResult remoteHeadBranch(String repoPath) {
            return new GitResult(true, "main");
        }

        @Override
        public GitResult ffOnly(String repoPath, String upstreamRef) {
            return new GitResult(true, "");
        }
    }

    private GitRepositoryEntity newReadyRepo(String defaultBranch, boolean manual) {
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setName("r");
        e.setLocalPath("D:/tmp/fake");
        e.setRemoteUrl("https://git.example.com/org/r.git");
        e.setSourceType(GitRepositoryEntity.SOURCE_CLONE);
        e.setCloneStatus(GitRepositoryEntity.CLONE_READY);
        e.setStatus(GitRepositoryEntity.STATUS_ACTIVE);
        e.setDefaultBranch(defaultBranch);
        e.setDefaultBranchManual(manual);
        return gitRepos.add(e);
    }

    @BeforeEach
    void setUp() {
        gitRepos = new FakeGitRepoRepository();
        projectRepos = new FakeProjectRepoRepository();
        mirroredProjectIds = new ArrayList<>();
        gitOps = new StubGitRemoteOps();
        ProjectService projectService = new ProjectService(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null) {
            @Override
            public void syncPrimaryMirror(String projectId) {
                mirroredProjectIds.add(projectId);
            }
        };
        CloneTokenResolver tokenResolver = new CloneTokenResolver(null, null) {
            @Override
            public String resolve(Long integrationId, String remoteUrl) {
                return null;
            }
        };
        service = new GitRepoSyncService(gitRepos.jpa(), projectRepos.jpa(), projectService,
                tokenResolver, gitOps);
    }

    @Test
    void fetchKeepsManualDefaultBranch() {
        // 用户把默认分支从 main 改成 dev，抓取后不得还原成远端 HEAD(main)
        GitRepositoryEntity repo = newReadyRepo("dev", true);
        ProjectRepoEntity linked = new ProjectRepoEntity();
        linked.setProjectId("p1");
        linked.setGitRepoId(repo.getId());
        linked.setDefaultBranch("dev");
        projectRepos.add(linked);

        service.fetchOne(repo.getId());

        assertEquals("dev", gitRepos.store.get(repo.getId()).getDefaultBranch());
        assertEquals("dev", projectRepos.store.get(linked.getId()).getDefaultBranch());
        assertEquals("dev\nmain", gitRepos.store.get(repo.getId()).getBranches());
        assertNull(gitRepos.store.get(repo.getId()).getLastFetchError());
    }

    @Test
    void fetchTracksRemoteHeadDriftWhenNotManual() {
        // 未手工指定：远端 HEAD master→main 漂移时自动更新并镜像关联行
        GitRepositoryEntity repo = newReadyRepo("master", false);
        ProjectRepoEntity linked = new ProjectRepoEntity();
        linked.setProjectId("p1");
        linked.setGitRepoId(repo.getId());
        linked.setDefaultBranch("master");
        projectRepos.add(linked);

        service.fetchOne(repo.getId());

        assertEquals("main", gitRepos.store.get(repo.getId()).getDefaultBranch());
        assertEquals("main", projectRepos.store.get(linked.getId()).getDefaultBranch());
        assertEquals(List.of("p1"), mirroredProjectIds);
    }

    @Test
    void fetchNoDriftLeavesBranchUntouched() {
        GitRepositoryEntity repo = newReadyRepo("main", false);

        service.fetchOne(repo.getId());

        assertEquals("main", gitRepos.store.get(repo.getId()).getDefaultBranch());
        assertEquals(List.of(), mirroredProjectIds);
    }
}
