package com.devmind.common.egress;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * CAP-70 FR-06：规则驱动 {@link ProxySelector}——命中 egress_rules → 本机 SOCKS5，
 * 未命中 → DIRECT。<b>显式挂载</b>到各连接器/探测客户端的 HttpClient.Builder，
 * 不设 JVM 全局默认（防意外流量被全量导进隧道）。
 *
 * <p>router 为 null（agent 模块未装配）= 恒 DIRECT。命中但隧道不可用时
 * {@link EgressProxyRouter#proxyFor} 抛出的 DevMindException 原样透出（fail-visible，
 * JDK HttpClient 会把它作为失败原因带回调用方），不静默回落 DIRECT。</p>
 */
public class EgressProxySelector extends ProxySelector {

    private final EgressProxyRouter router;

    public EgressProxySelector(EgressProxyRouter router) {
        this.router = router;
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (router != null && uri != null) {
            return router.proxyFor(uri.getHost()).map(List::of).orElse(List.of(Proxy.NO_PROXY));
        }
        return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // 不回落：失败原因已由调用链呈现（FR-07），ProxySelector 语义上无重选义务
    }
}
