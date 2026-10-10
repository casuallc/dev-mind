package com.devmind.agent.controller;

import com.devmind.agent.dto.EgressRuleRequest;
import com.devmind.agent.dto.EgressRuleView;
import com.devmind.agent.dto.EgressTunnelStatusView;
import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.agent.service.EgressTunnelStatus;
import com.devmind.auth.IdentityService;
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
    private final AgentAuditLogService auditLogService;
    private final IdentityService identityService;

    public EgressRuleController(EgressRuleService service, AgentNodeRepository nodeRepo,
                                ObjectProvider<EgressTunnelStatus> tunnelStatus,
                                AgentAuditLogService auditLogService, IdentityService identityService) {
        this.service = service;
        this.nodeRepo = nodeRepo;
        this.tunnelStatus = tunnelStatus;
        this.auditLogService = auditLogService;
        this.identityService = identityService;
    }

    @GetMapping
    public List<EgressRuleView> list() {
        return service.list(tunnelStatusOrNull());
    }

    @PostMapping
    public EgressRuleView create(@Valid @RequestBody EgressRuleRequest req) {
        EgressRuleView v = service.create(req, tunnelStatusOrNull());
        audit("CREATE", v.hostPattern(), v.nodeId(), "enabled=" + v.enabled() + " sort=" + v.sort());
        return v;
    }

    @PutMapping("/{id}")
    public EgressRuleView update(@PathVariable Long id, @Valid @RequestBody EgressRuleRequest req) {
        EgressRuleView v = service.update(id, req, tunnelStatusOrNull());
        audit("UPDATE", v.hostPattern(), v.nodeId(),
                "id=" + id + " enabled=" + v.enabled() + " sort=" + v.sort());
        return v;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
        audit("DELETE", "rule#" + id, null, null);
    }

    /** CAP-71：出口规则变更审计（RULE 分类，带操作人）。 */
    private void audit(String event, String target, Long nodeId, String detail) {
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_RULE, event)
                .actor(identityService.currentActor())
                .nodeId(nodeId).target(target)
                .result(AgentAuditLogEntity.RESULT_OK).detail(detail));
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
