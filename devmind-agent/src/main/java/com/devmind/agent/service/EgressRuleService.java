package com.devmind.agent.service;

import com.devmind.agent.dto.EgressRuleRequest;
import com.devmind.agent.dto.EgressRuleView;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.agent.repo.EgressRuleRepository;
import com.devmind.common.egress.EgressHostMatcher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-70 FR-03：egress_rules 平台级出口规则（服务端 DB 权威，即白名单）。
 * 路由查询走内存快照（volatile 全量表，变更即失效重建）——ProxySelector 每请求都会
 * 调路由判定，不能逐请求打 DB。规则变更发布 {@link EgressRulesChangedEvent}，
 * 隧道注册表据此向在线隧道重推 allowedHosts 快照（FR-04）。
 */
@Service
public class EgressRuleService {

    private final EgressRuleRepository repo;
    private final AgentNodeRepository nodeRepo;
    private final ApplicationEventPublisher events;

    /** 路由快照：启用规则按 sort 升序（先命中先生效）；null = 待加载 */
    private volatile List<EgressRuleEntity> routeCache;

    public EgressRuleService(EgressRuleRepository repo, AgentNodeRepository nodeRepo,
                             ApplicationEventPublisher events) {
        this.repo = repo;
        this.nodeRepo = nodeRepo;
        this.events = events;
    }

    /** 规则列表（含节点名/协议版本/隧道在线状态） */
    public List<EgressRuleView> list(EgressTunnelStatus tunnelStatus) {
        Map<Long, AgentNodeEntity> nodes = nodeRepo.findAll().stream()
                .collect(Collectors.toMap(AgentNodeEntity::getId, Function.identity()));
        return repo.findAllByOrderBySortAscIdAsc().stream().map(r -> {
            AgentNodeEntity n = nodes.get(r.getNodeId());
            return new EgressRuleView(r.getId(), r.getHostPattern(), r.getNodeId(),
                    n == null ? null : n.getName(), r.isEnabled(), r.getSort(), r.getRemark(),
                    n == null ? null : n.getProtocolVersion(),
                    tunnelStatus == null ? null : tunnelStatus.tunnelOnline(r.getNodeId()),
                    r.getCreatedAt(), r.getUpdatedAt());
        }).toList();
    }

    @Transactional
    public EgressRuleView create(EgressRuleRequest req, EgressTunnelStatus tunnelStatus) {
        EgressRuleEntity e = new EgressRuleEntity();
        apply(e, req);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        EgressRuleEntity saved = repo.save(e);
        afterChange();
        return list(tunnelStatus).stream().filter(v -> v.id().equals(saved.getId())).findFirst().orElseThrow();
    }

    @Transactional
    public EgressRuleView update(Long id, EgressRuleRequest req, EgressTunnelStatus tunnelStatus) {
        EgressRuleEntity e = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "出口规则不存在: " + id));
        apply(e, req);
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        afterChange();
        return list(tunnelStatus).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "出口规则不存在: " + id);
        }
        repo.deleteById(id);
        afterChange();
    }

    /** 路由判定：host 命中的首条启用规则；未命中 = empty（直连，零行为变化） */
    public Optional<EgressRuleEntity> findRoute(String host) {
        String h = EgressHostMatcher.normalizeHost(host);
        if (h.isEmpty()) {
            return Optional.empty();
        }
        for (EgressRuleEntity r : routeSnapshot()) {
            if (EgressHostMatcher.matches(r.getHostPattern(), h)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** 节点放行快照（tunnel_hello 的 allowedHosts：该节点全部启用规则的 pattern） */
    public List<String> allowedHostsFor(Long nodeId) {
        return routeSnapshot().stream()
                .filter(r -> r.getNodeId().equals(nodeId))
                .map(EgressRuleEntity::getHostPattern)
                .toList();
    }

    private List<EgressRuleEntity> routeSnapshot() {
        List<EgressRuleEntity> c = routeCache;
        if (c == null) {
            synchronized (this) {
                c = routeCache;
                if (c == null) {
                    c = repo.findAllByOrderBySortAscIdAsc().stream()
                            .filter(EgressRuleEntity::isEnabled).toList();
                    routeCache = c;
                }
            }
        }
        return c;
    }

    private void apply(EgressRuleEntity e, EgressRuleRequest req) {
        String pattern = EgressHostMatcher.normalizePattern(req.hostPattern());
        if (pattern.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "host 规则非法: " + req.hostPattern() + "（支持 gitlab.corp.com 或 *.corp.com）");
        }
        if (req.nodeId() == null || !nodeRepo.existsById(req.nodeId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "出口节点不存在: " + req.nodeId());
        }
        e.setHostPattern(pattern);
        e.setNodeId(req.nodeId());
        e.setEnabled(req.enabled() == null || req.enabled());
        e.setSort(req.sort() == null ? 0 : req.sort());
        e.setRemark(req.remark());
    }

    private void afterChange() {
        routeCache = null;
        events.publishEvent(new EgressRulesChangedEvent());
    }

    /** 规则变更事件（隧道注册表监听后向在线隧道重推快照） */
    public record EgressRulesChangedEvent() {
    }
}
