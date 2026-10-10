package com.devmind.agent.service;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.agent.tunnel.AgentTunnelRegistry;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.egress.EgressHostMatcher;
import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * CAP-70 FR-05/06/07：{@link EgressProxyRouter} 实现（规则表 + 隧道状态 + 代理端口）。
 * Java HTTP 侧给 HTTP（CONNECT）代理（JDK HttpClient 静默丢弃 SOCKS，见 SPI javadoc）；
 * git 侧给 socks5h（git 原生支持且 DNS 必须代理解析）。
 * 命中规则但不可用 = 抛 DevMindException(CONFLICT)，文案带规则与节点状态（fail-visible，
 * 禁静默回落直连）；未命中 = empty（直连，零行为变化）。
 */
@Component
public class EgressProxyRouterImpl implements EgressProxyRouter {

    private static final Logger log = LoggerFactory.getLogger(EgressProxyRouterImpl.class);

    private final EgressRuleService ruleService;
    private final AgentNodeRepository nodeRepo;
    private final AgentConnectionRegistry connectionRegistry;
    private final AgentTunnelRegistry tunnelRegistry;
    private final EgressProperties props;

    public EgressProxyRouterImpl(EgressRuleService ruleService, AgentNodeRepository nodeRepo,
                                 AgentConnectionRegistry connectionRegistry,
                                 AgentTunnelRegistry tunnelRegistry, EgressProperties props) {
        this.ruleService = ruleService;
        this.nodeRepo = nodeRepo;
        this.connectionRegistry = connectionRegistry;
        this.tunnelRegistry = tunnelRegistry;
        this.props = props;
    }

    @Override
    public Optional<Proxy> proxyFor(String host) {
        Optional<EgressRuleEntity> route = ruleService.findRoute(host);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        checkUsable(route.get(), host);
        // HTTP（CONNECT）代理而非 SOCKS：JDK HttpClient 只认 Proxy.Type.HTTP，SOCKS 代理会被
        // 静默丢弃直连（java.net.http 源码零 socks 处理，2026-10-10 实锤）；CONNECT 与
        // socks5h 同语义——目标主机名由隧道对端（runner）解析，服务端本机无需解析内网域名。
        return Optional.of(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", props.getHttpPort())));
    }

    @Override
    public Optional<String> gitProxyUrl(String remoteUrl) {
        String host = hostOfHttpUrl(remoteUrl);
        if (host == null) {
            return Optional.empty(); // file:// / ssh / 无 host：天然直连
        }
        return proxyFor(host).map(p -> "socks5h://127.0.0.1:" + props.getSocksPort());
    }

    /** FR-07：命中规则但不可用 → 快速失败，文案带规则名与节点状态 */
    private void checkUsable(EgressRuleEntity rule, String host) {
        if (!props.isEnabled()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "出口规则「" + rule.getHostPattern() + "」命中 " + host
                            + "，但出口隧道已禁用（devmind.egress.enabled=false）");
        }
        AgentNodeEntity node = nodeRepo.findById(rule.getNodeId()).orElse(null);
        String nodeName = node == null ? ("#" + rule.getNodeId()) : node.getName();
        if (node == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "出口规则「" + rule.getHostPattern() + "」命中 " + host
                            + "，但出口节点 #" + rule.getNodeId() + " 已被删除，请编辑规则换节点或删除规则");
        }
        String nodeId = String.valueOf(node.getId());
        if (!connectionRegistry.isOnline(nodeId)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "出口规则「" + rule.getHostPattern() + "」命中 " + host
                            + "，但出口节点 " + nodeName + " 离线（禁静默回落直连，请到节点页确认节点状态）");
        }
        if (!connectionRegistry.supports(nodeId, AgentProtocol.EGRESS_TUNNEL)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "出口规则「" + rule.getHostPattern() + "」命中 " + host + "，但出口节点 " + nodeName
                            + " 的 runner 协议版本过低（出口隧道需 v" + AgentProtocol.EGRESS_TUNNEL
                            + "+），请到节点页升级 runner");
        }
        if (!tunnelRegistry.tunnelOnline(node.getId())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "出口规则「" + rule.getHostPattern() + "」命中 " + host + "，出口节点 " + nodeName
                            + " 在线但隧道未连接（runner 隧道通道建立中或已断开，稍后重试；持续不通请查 runner 日志）");
        }
        log.debug("出口路由: {} → 规则「{}」→ 节点 {} 隧道", host, rule.getHostPattern(), nodeName);
    }

    /** 取 http(s) URL 的 host；非 http(s)/无 host/解析失败 → null */
    private static String hostOfHttpUrl(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(remoteUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String host = EgressHostMatcher.normalizeHost(uri.getHost());
            return host.isEmpty() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }
}
