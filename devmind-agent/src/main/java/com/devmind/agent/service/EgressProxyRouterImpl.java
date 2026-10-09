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
 * CAP-70 FR-05/06/07：{@link EgressProxyRouter} 实现（规则表 + 隧道状态 + SOCKS 端口）。
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
        return Optional.of(new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", props.getSocksPort())));
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
