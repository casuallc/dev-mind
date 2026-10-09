package com.devmind.common.egress;

import java.net.Proxy;
import java.util.Optional;

/**
 * CAP-70：服务端出口路由 SPI——按平台级 egress_rules 域名规则表决定「直连 or 经哪个节点
 * 的反向隧道出访」。接口定义在 common，实现方 devmind-agent（规则表 + 隧道状态 + SOCKS
 * 端口都在该模块），消费方以 {@code ObjectProvider<EgressProxyRouter>} 探测注入——
 * 未装配 agent 模块 = 全直连（兼容无 agent 模块的装配形态）。
 *
 * <p>失败语义（FR-07，fail-visible）：host 命中规则但出口节点离线/隧道断开/协议版本不足时
 * 抛 {@code DevMindException(CONFLICT)}，文案带规则与节点状态——<b>禁静默回落直连</b>
 * （规则命中即「用户明确声明此 host 须走内网」，静默直连会把配置错误伪装成偶发超时）。</p>
 */
public interface EgressProxyRouter {

    /**
     * Java HTTP 客户端出口：host 命中规则且隧道可用 → 本机 SOCKS5 代理；未命中 → empty（直连）。
     * 命中但不可用 → 抛 DevMindException（fail-visible）。
     */
    Optional<Proxy> proxyFor(String host);

    /**
     * git 出口：remoteUrl 的 host 命中规则且隧道可用 → {@code socks5h://127.0.0.1:<port>}
     * （socks5h = 主机名由 runner 侧解析，服务端在外网本就解析不了内网域名）；
     * 未命中/非 http(s)/无 host（file://）→ empty。命中但不可用 → 抛 DevMindException。
     */
    Optional<String> gitProxyUrl(String remoteUrl);
}
