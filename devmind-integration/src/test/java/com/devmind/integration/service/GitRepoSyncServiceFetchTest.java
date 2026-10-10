package com.devmind.integration.service;

import com.devmind.project.ProjectService;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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

    @TempDir
    Path tempDir;

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

    /** 远端 HEAD 固定返回 main；fetch/克隆/分支列表/ff 全成功，记录调用次数供路由断言。 */
    static class StubGitRemoteOps extends GitRemoteOps {
        int fetchCalls = 0;
        int cloneCalls = 0;

        StubGitRemoteOps() {
            // 全部网络方法已覆盖，出口路由器永不触达——传空 ObjectProvider 即可
            super(new org.springframework.beans.factory.ObjectProvider<>() {
                @Override
                public com.devmind.common.egress.EgressProxyRouter getObject() {
                    return null;
                }

                @Override
                public com.devmind.common.egress.EgressProxyRouter getObject(Object... args) {
                    return null;
                }
            });
        }

        @Override
        public GitResult fetchAllRefs(String repoPath, String remoteUrl, String token) {
            fetchCalls++;
            return new GitResult(true, "");
        }

        @Override
        public GitResult cloneRepo(String remoteUrl, String token, String targetDir, String branch,
                                   Consumer<String> lineSink) {
            cloneCalls++;
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

    /** 建 READY 行并配真实存在的本地克隆目录（含 .git），走 fetch 路径。 */
    private GitRepositoryEntity newReadyRepo(String defaultBranch, boolean manual) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("clone-" + gitRepos.store.size()).resolve(".git"));
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setName("r");
        e.setLocalPath(dir.getParent().toString());
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
    void fetchKeepsManualDefaultBranch() throws IOException {
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
    void fetchTracksRemoteHeadDriftWhenNotManual() throws IOException {
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
    void fetchNoDriftLeavesBranchUntouched() throws IOException {
        GitRepositoryEntity repo = newReadyRepo("main", false);

        service.fetchOne(repo.getId());

        assertEquals("main", gitRepos.store.get(repo.getId()).getDefaultBranch());
        assertEquals(List.of(), mirroredProjectIds);
    }

    @Test
    void fetchWithMissingLocalCloneSelfHealsViaClone() throws IOException {
        // 迁移只搬了 DB：CLONE_READY 行的 localPath 在磁盘上不存在，fetch 须自动转全量克隆
        // 而不是在缺失目录里跑 git 抛 "cannot change to ..."（194 迁移后 deploy-flow 实测事故）
        GitRepositoryEntity repo = newReadyRepo("main", false);
        repo.setLocalPath(tempDir.resolve("gone").toString());

        service.fetchOne(repo.getId());

        assertEquals(1, gitOps.cloneCalls);
        assertEquals(0, gitOps.fetchCalls);
        GitRepositoryEntity after = gitRepos.store.get(repo.getId());
        assertEquals(GitRepositoryEntity.CLONE_READY, after.getCloneStatus());
        assertNull(after.getCloneError());
        assertEquals("dev\nmain", after.getBranches());
    }
}
