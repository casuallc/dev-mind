package com.devmind.agent.controller;

import com.devmind.agent.dto.EgressRuleRequest;
import com.devmind.agent.dto.EgressRuleView;
import com.devmind.agent.dto.EgressTunnelStatusView;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.agent.service.EgressTunnelStatus;
import com.devmind.common.agent.AgentProtocol;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-70 FR-03/FR-08：平台级出口规则管理。全方法（含 GET）仅 ADMIN——
 * 规则表即内网出口白名单，收紧在 SecurityConfig。
 */
@RestController
@RequestMapping("/api/egress-rules")
public class EgressRuleController {

    private final EgressRuleService service;
    private final AgentNodeRepository nodeRepo;
    private final ObjectProvider<EgressTunnelStatus> tunnelStatus;

    public EgressRuleController(EgressRuleService service, AgentNodeRepository nodeRepo,
                                ObjectProvider<EgressTunnelStatus> tunnelStatus) {
        this.service = service;
        this.nodeRepo = nodeRepo;
        this.tunnelStatus = tunnelStatus;
    }

    @GetMapping
    public List<EgressRuleView> list() {
        return service.list(tunnelStatusOrNull());
    }

    @PostMapping
    public EgressRuleView create(@Valid @RequestBody EgressRuleRequest req) {
        return service.create(req, tunnelStatusOrNull());
    }

    @PutMapping("/{id}")
    public EgressRuleView update(@PathVariable Long id, @Valid @RequestBody EgressRuleRequest req) {
        return service.update(id, req, tunnelStatusOrNull());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 各节点隧道在线状态（FR-08 徽标数据源） */
    @GetMapping("/status")
    public List<EgressTunnelStatusView> status() {
        EgressTunnelStatus ts = tunnelStatusOrNull();
        return nodeRepo.findAll().stream()
                .map(n -> toStatusView(n, ts))
                .toList();
    }

    private EgressTunnelStatusView toStatusView(AgentNodeEntity n, EgressTunnelStatus ts) {
        int pv = n.getProtocolVersion() == null ? AgentProtocol.DEFAULT_WHEN_ABSENT : n.getProtocolVersion();
        return new EgressTunnelStatusView(n.getId(), n.getName(), n.getStatus(), n.getProtocolVersion(),
                pv >= AgentProtocol.EGRESS_TUNNEL,
                ts != null && ts.tunnelOnline(n.getId()));
    }

    private EgressTunnelStatus tunnelStatusOrNull() {
        return tunnelStatus.getIfAvailable();
    }
}
