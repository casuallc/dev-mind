package com.devmind.integration.service;

import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-70 FR-05：GitRemoteOps 出口代理注入组命令参数。
 * 命中规则 → [-c http.<scheme://host[:port]>.proxy=socks5h://...]；未命中/无 router/空 URL → 空表；
 * 命中但不可用（router 抛 CONFLICT）→ 原样上抛（fail-visible，禁静默回落直连）。
 */
class GitRemoteOpsEgressTest {

    private static GitRemoteOps ops(EgressProxyRouter router) {
        ObjectProvider<EgressProxyRouter> provider = new ObjectProvider<>() {
            @Override
            public EgressProxyRouter getObject() {
                return router;
            }

            @Override
            public EgressProxyRouter getObject(Object... args) {
                return router;
            }

            @Override
            public EgressProxyRouter getIfAvailable() {
                return router;
            }
        };
        return new GitRemoteOps(provider);
    }

    /** 只实现 gitProxyUrl 的最小 router 桩 */
    private static EgressProxyRouter routerReturning(Optional<String> gitProxy) {
        return new EgressProxyRouter() {
            @Override
            public Optional<Proxy> proxyFor(String host) {
                return Optional.empty();
            }

            @Override
            public Optional<String> gitProxyUrl(String remoteUrl) {
                return gitProxy;
            }
        };
    }

    @Test
    void noRouterMeansEmptyArgs() {
        GitRemoteOps ops = ops(null);
        assertTrue(ops.egressProxyArgs("https://git.corp.com/g/r.git").isEmpty());
    }

    @Test
    void blankOrNullUrlMeansEmptyArgs() {
        GitRemoteOps ops = ops(routerReturning(Optional.of("socks5h://127.0.0.1:18089")));
        assertTrue(ops.egressProxyArgs(null).isEmpty());
        assertTrue(ops.egressProxyArgs("  ").isEmpty());
    }

    @Test
    void unmatchedRuleMeansEmptyArgs() {
        GitRemoteOps ops = ops(routerReturning(Optional.empty()));
        assertTrue(ops.egressProxyArgs("https://git.corp.com/g/r.git").isEmpty());
    }

    @Test
    void matchedRuleInjectsPerUrlProxyConfig() {
        GitRemoteOps ops = ops(routerReturning(Optional.of("socks5h://127.0.0.1:18089")));
        List<String> args = ops.egressProxyArgs("https://git.corp.com:8443/g/r.git");
        assertEquals(List.of("-c", "http.https://git.corp.com:8443.proxy=socks5h://127.0.0.1:18089"), args);
    }

    @Test
    void defaultPortOmittedFromConfigKey() {
        GitRemoteOps ops = ops(routerReturning(Optional.of("socks5h://127.0.0.1:18089")));
        List<String> args = ops.egressProxyArgs("https://git.corp.com/g/r.git");
        assertEquals(List.of("-c", "http.https://git.corp.com.proxy=socks5h://127.0.0.1:18089"), args);
    }

    @Test
    void unavailableEgressFailsVisibly() {
        EgressProxyRouter router = new EgressProxyRouter() {
            @Override
            public Optional<Proxy> proxyFor(String host) {
                return Optional.empty();
            }

            @Override
            public Optional<String> gitProxyUrl(String remoteUrl) {
                throw new DevMindException(ErrorCode.CONFLICT, "出口规则 git.corp.com 的节点离线");
            }
        };
        DevMindException e = assertThrows(DevMindException.class,
                () -> ops(router).egressProxyArgs("https://git.corp.com/g/r.git"));
        assertTrue(e.getMessage().contains("离线"));
    }

    @Test
    void configKeyDerivedFromCleanUrlNotTokenUrl() {
        // 防御：带 userinfo 的 URL 也不应把凭据泄进配置键（调用方传 cleanUrl，此处再钉一层）
        AtomicReference<String> seen = new AtomicReference<>();
        EgressProxyRouter router = new EgressProxyRouter() {
            @Override
            public Optional<Proxy> proxyFor(String host) {
                return Optional.empty();
            }

            @Override
            public Optional<String> gitProxyUrl(String remoteUrl) {
                seen.set(remoteUrl);
                return Optional.of("socks5h://127.0.0.1:18089");
            }
        };
        // 即使误传带 token 的 URL，配置键也只取 scheme://host[:port]
        List<String> args = ops(router).egressProxyArgs("https://oauth2:secret@git.corp.com/g/r.git");
        assertEquals(List.of("-c", "http.https://git.corp.com.proxy=socks5h://127.0.0.1:18089"), args);
    }
}
