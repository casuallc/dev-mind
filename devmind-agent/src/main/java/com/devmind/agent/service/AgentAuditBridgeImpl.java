package com.devmind.agent.service;

import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.repo.AgentAuditLogRepository;
import com.devmind.common.agent.AgentAuditBridge;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-115 {@link AgentAuditBridge} 实现：COMMAND 类审计的按 target 倒序查询与带操作人补记。
 * 查询只投影只读字段；补记走 {@link AgentAuditLogService#record} 旁路落库（失败仅降级日志）。
 */
@Component
public class AgentAuditBridgeImpl implements AgentAuditBridge {

    /** target 查询硬上限（与服务端审计页 MAX_PAGE_SIZE 同量级） */
    private static final int MAX_LIMIT = 200;

    private final AgentAuditLogRepository repo;
    private final AgentAuditLogService auditLogService;

    public AgentAuditBridgeImpl(AgentAuditLogRepository repo, AgentAuditLogService auditLogService) {
        this.repo = repo;
        this.auditLogService = auditLogService;
    }

    @Override
    public List<Entry> recentCommandsByTarget(String target, int limit) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repo.findByCategoryAndTargetOrderByCreatedAtDescIdDesc(
                        AgentAuditLogEntity.CAT_COMMAND, target, PageRequest.of(0, n))
                .stream()
                .map(a -> new Entry(a.getCreatedAt(), a.getEvent(), a.getNodeName(), a.getActor(),
                        a.getTarget(), a.getResult(), a.getDurationMs(), a.getDetail()))
                .toList();
    }

    @Override
    public void recordCommand(String event, String nodeId, String target, boolean ok,
                              String actor, String detail) {
        Long nid = null;
        try {
            nid = nodeId == null || nodeId.isBlank() ? null : Long.valueOf(nodeId);
        } catch (NumberFormatException ignored) {
            // 非数字 nodeId 不阻断补记
        }
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_COMMAND, event)
                .nodeId(nid)
                .actor(actor)
                .target(target)
                .result(ok ? AgentAuditLogEntity.RESULT_OK : AgentAuditLogEntity.RESULT_FAIL)
                .detail(detail));
    }
}
