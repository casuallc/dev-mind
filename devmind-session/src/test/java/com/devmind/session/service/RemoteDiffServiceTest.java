package com.devmind.session.service;

import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.project.GitRepoService;
import com.devmind.project.config.ProjectProperties;
import com.devmind.session.dto.RepoDiffView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionRepoEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-31 {@link RemoteDiffService} 测试：file:// 匿名通道当远端（无需凭据）。
 * 场景：runner 结束已 push 会话分支 → 服务端克隆缓存 fetch 后三点 diff；
 * 分支未推送 / ssh 协议 / 克隆未就绪 → 只填该行 error。
 */
class RemoteDiffServiceTest {

    @TempDir
    Path tmp;

    private GitRepoService gitRepoService;
    private RemoteDiffService service;

    @BeforeEach
    void setUp() {
        ProjectProperties props = new ProjectProperties();
        props.setWorkspaceRoot(tmp.resolve("ws").toString());
        // deriveClonePath 只用 props（其余依赖本测试路径不触达）
        gitRepoService = new GitRepoService(null, null, props, null, null);
        service = new RemoteDiffService(gitRepoService, null, emptyProvider(), egressProvider(null));
    }

    /** CAP-70 FR-05：命中出口规则 → fetch 组命令带 -c http.<scheme://host>.proxy=socks5h://... */
    @Test
    void egressRuleInjectsProxyConfig() {
        RemoteDiffService s = new RemoteDiffService(gitRepoService, null, emptyProvider(),
                egressProvider(routerReturning(Optional.of("socks5h://127.0.0.1:18089"))));
        List<String> args = s.egressProxyArgs("https://git.corp.com:8443/g/r.git");
        assertEquals(List.of("-c", "http.https://git.corp.com:8443.proxy=socks5h://127.0.0.1:18089"), args);
    }

    /** CAP-70 FR-09：无 router / 未命中规则 → 空表零行为变化 */
    @Test
    void noRouterOrUnmatchedMeansNoProxyArgs() {
        assertTrue(service.egressProxyArgs("https://git.corp.com/g/r.git").isEmpty());
        RemoteDiffService s = new RemoteDiffService(gitRepoService, null, emptyProvider(),
                egressProvider(routerReturning(Optional.empty())));
        assertTrue(s.egressProxyArgs("https://git.corp.com/g/r.git").isEmpty());
    }

    /** CAP-70 FR-07：命中但不可用 → router 抛 CONFLICT 原样上行（fail-visible） */
    @Test
    void unavailableEgressFailsVisibly() {
        EgressProxyRouter router = new EgressProxyRouter() {
            @Override
            public java.util.Optional<java.net.Proxy> proxyFor(String host) {
                return java.util.Optional.empty();
            }

            @Override
            public Optional<String> gitProxyUrl(String remoteUrl) {
                throw new com.devmind.common.exception.DevMindException(
                        com.devmind.common.exception.ErrorCode.CONFLICT, "出口规则 git.corp.com 隧道离线");
            }
        };
        RemoteDiffService s = new RemoteDiffService(gitRepoService, null, emptyProvider(),
                egressProvider(router));
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                com.devmind.common.exception.DevMindException.class,
                () -> s.egressProxyArgs("https://git.corp.com/g/r.git"));
        assertTrue(e.getMessage().contains("隧道离线"));
    }

    @Test
    void diffAfterRunnerPush() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, true);
        String url = origin.toUri().toString();
        cloneCache(url);

        List<RepoDiffView> views = service.diff(session("s1"),
                List.of(row("backend", url, "main", "feature/s1")));

        assertEquals(1, views.size());
        RepoDiffView v = views.get(0);
        assertEquals("backend", v.repoName());
        assertTrue(v.primary());
        assertNull(v.error());
        assertTrue(v.hasChanges());
        assertTrue(v.files().contains("code.txt"), String.join(",", v.files()));
        assertTrue(v.stat().contains("code.txt"), v.stat());
    }

    @Test
    void branchNotPushedYetIsErrorRow() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, false); // 只有 main，无 feature/s1
        String url = origin.toUri().toString();
        cloneCache(url);

        List<RepoDiffView> views = service.diff(session("s1"),
                List.of(row("backend", url, "main", "feature/s1")));

        assertEquals(1, views.size());
        // v19：文案覆盖两种情形——尚未收口（未推送）/ 收口时勾选了删除远端分支
        assertTrue(views.get(0).error() != null && views.get(0).error().contains("不在远端"),
                views.get(0).error());
    }

    @Test
    void sshRemoteIsErrorRow() {
        List<RepoDiffView> views = service.diff(session("s1"),
                List.of(row("backend", "git@github.com:org/x.git", "main", "feature/s1")));
        assertTrue(views.get(0).error().contains("http"), views.get(0).error());
    }

    @Test
    void cloneNotReadyIsErrorRow() {
        List<RepoDiffView> views = service.diff(session("s1"),
                List.of(row("backend", "https://example.com/org/not-cloned.git", "main", "feature/s1")));
        assertTrue(views.get(0).error().contains("克隆未就绪"), views.get(0).error());
    }

    /** 多库时单库失败不拖垮整组 */
    @Test
    void singleRepoFailureDoesNotBreakOthers() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, true);
        String url = origin.toUri().toString();
        cloneCache(url);

        List<RepoDiffView> views = service.diff(session("s1"), List.of(
                row("backend", url, "main", "feature/s1"),
                row("docs", "https://example.com/org/not-cloned.git", "main", "feature/s1")));

        assertEquals(2, views.size());
        assertNull(views.get(0).error());
        assertTrue(views.get(0).hasChanges());
        assertTrue(views.get(1).error() != null);
    }

    // ---------------- 内部 ----------------

    private void cloneCache(String url) throws Exception {
        Path cache = gitRepoService.deriveClonePath(url);
        Files.createDirectories(cache.getParent());
        git(tmp, "clone", url, cache.toString());
    }

    private void seedOrigin(Path origin, boolean pushFeatureBranch) throws Exception {
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed-" + origin.getFileName());
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
        if (pushFeatureBranch) {
            git(seed, "checkout", "-b", "feature/s1");
            Files.writeString(seed.resolve("code.txt"), "change");
            git(seed, "add", ".");
            git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
            git(seed, "push", "origin", "feature/s1");
        }
    }

    private static SessionEntity session(String id) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setProjectId("p1");
        e.setBaseBranch("main");
        e.setCreatedBy("tester");
        return e;
    }

    private static SessionRepoEntity row(String name, String remoteUrl, String baseBranch, String branch) {
        SessionRepoEntity e = new SessionRepoEntity();
        e.setSessionId("s1");
        e.setName(name);
        e.setRemoteUrl(remoteUrl);
        e.setBaseBranch(baseBranch);
        e.setBranch(branch);
        e.setIsPrimary(true);
        e.setSortOrder(0);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RepoGitGateway> emptyProvider() {
        return (ObjectProvider<RepoGitGateway>) Proxy.newProxyInstance(
                RemoteDiffServiceTest.class.getClassLoader(),
                new Class<?>[] { ObjectProvider.class },
                (p, m, args) -> switch (m.getName()) {
                    case "getIfAvailable", "getIfUnique" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EgressProxyRouter> egressProvider(EgressProxyRouter router) {
        return (ObjectProvider<EgressProxyRouter>) Proxy.newProxyInstance(
                RemoteDiffServiceTest.class.getClassLoader(),
                new Class<?>[] { ObjectProvider.class },
                (p, m, args) -> switch (m.getName()) {
                    case "getIfAvailable", "getIfUnique" -> router;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** 只实现 gitProxyUrl 的最小 router 桩 */
    private static EgressProxyRouter routerReturning(Optional<String> gitProxy) {
        return new EgressProxyRouter() {
            @Override
            public java.util.Optional<java.net.Proxy> proxyFor(String host) {
                return java.util.Optional.empty();
            }

            @Override
            public Optional<String> gitProxyUrl(String remoteUrl) {
                return gitProxy;
            }
        };
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
    }
}
