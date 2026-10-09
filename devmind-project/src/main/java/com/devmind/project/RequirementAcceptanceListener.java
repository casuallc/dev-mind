package com.devmind.project;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 测试事件 → 验收联动（反向通道）：消费 test.completed 成功事件（workItemId 缺省时 TestRunService
 * 已从来源部署继承），先幂等 rollup（工作单元全终态 → ACCEPTANCE），需求落定 ACCEPTANCE 后广播
 * requirement.acceptance.ready（通知订阅转 P2「回归通过 · 需求待验收」）。
 * 仍有活跃工作单元 / 已 DONE / CANCELLED 的需求不动作——回归全绿不代表开发完结，验收节奏由 rollup 与人共管。
 */
@Component
public class RequirementAcceptanceListener {

    private static final Logger log = LoggerFactory.getLogger(RequirementAcceptanceListener.class);

    private final RequirementService requirementService;
    private final WorkItemService workItemService;
    private final DomainEventPublisher eventPublisher;

    public RequirementAcceptanceListener(RequirementService requirementService,
                                         WorkItemService workItemService,
                                         DomainEventPublisher eventPublisher) {
        this.requirementService = requirementService;
        this.workItemService = workItemService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onTestCompleted(SimpleDomainEvent event) {
        if (!"test.completed".equals(event.type()) || !Boolean.TRUE.equals(event.success())) {
            return;
        }
        String workItemId = event.workItemId();
        if (workItemId == null || workItemId.isBlank()) {
            return;
        }
        try {
            handle(event, workItemId);
        } catch (Exception e) {
            log.warn("验收联动处理失败(不阻塞): run={} workItemId={} err={}",
                    event.entityId(), workItemId, e.getMessage());
        }
    }

    /** 联动本体（包可见便于单测同步驱动）：rollup 后落定 ACCEPTANCE 才广播待验收。 */
    void handle(SimpleDomainEvent event, String workItemId) {
        WorkItemEntity wi = workItemService.requireById(workItemId);
        String requirementId = wi.getRequirementId();
        if (requirementId == null || requirementId.isBlank()) {
            return;
        }
        requirementService.recomputeStatus(requirementId);
        RequirementEntity req = requirementService.requireById(requirementId);
        if (!RequirementEntity.STATUS_ACCEPTANCE.equals(req.getStatus())) {
            return;
        }
        eventPublisher.publish(SimpleDomainEvent.of("requirement.acceptance.ready", req.getProjectId(),
                workItemId, "system",
                "回归通过（测试 #" + event.entityId() + "），需求 REQ-" + req.getSeq() + " 待验收：" + req.getTitle(),
                "REQUIREMENT", requirementId, null));
    }
}
