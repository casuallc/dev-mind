package com.devmind.flow;

import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.notification.dto.ActionDef;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.RelationService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.RelationView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.WorkItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作单元编排器（CAP-15）：依赖驱动的自动派发。
 * 语义边界——派发自动化，完成判定仍归人：WI 的 DONE 由人验收翻转，
 * 本编排器只负责"依赖就绪 → 自动起会话"（复用 CAP-14 {@link RequirementFlowService#startWorkItemSession}）。
 *
 * <p>触发点：① WI 翻转为 DONE（{@code workitem.status.changed} 事件）；② 拆分固化（{@code flow.split.confirmed} 事件）。
 * 并发满（TOO_MANY_SESSIONS）时本轮跳过，等下一个 DONE 事件重试，不排队不丢失。</p>
 */
@Component
public class WorkItemOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkItemOrchestrator.class);

    private final WorkItemService workItemService;
    private final RelationService relationService;
    private final RequirementFlowService flowService;
    private final NotificationService notificationService;

    public WorkItemOrchestrator(WorkItemService workItemService,
                                RelationService relationService,
                                RequirementFlowService flowService,
                                NotificationService notificationService) {
        this.workItemService = workItemService;
        this.relationService = relationService;
        this.flowService = flowService;
        this.notificationService = notificationService;
    }

    /** FR-01：WI 翻转为 DONE 时扫描同需求依赖就绪项并自动派发。 */
    @EventListener
    public void onWorkItemStatusChanged(SimpleDomainEvent event) {
        if (!"workitem.status.changed".equals(event.type()) || event.workItemId() == null) {
            return;
        }
        try {
            WorkItemEntity wi = workItemService.requireById(event.workItemId());
            if (!WorkItemEntity.STATUS_DONE.equals(wi.getStatus())) {
                return;
            }
            dispatchReady(wi.getProjectId(), wi.getRequirementId());
        } catch (Exception e) {
            log.warn("编排器处理状态变更事件失败(不阻塞): wi={} err={}", event.workItemId(), e.getMessage());
        }
    }

    /** FR-02：拆分固化后首批无依赖 WI 立即自动派发（"确认拆分 → 自动执行"闭环）。 */
    @EventListener
    public void onSplitConfirmed(SimpleDomainEvent event) {
        if (!"flow.split.confirmed".equals(event.type()) || event.entityId() == null) {
            return;
        }
        try {
            dispatchReady(event.projectId(), event.entityId());
        } catch (Exception e) {
            log.warn("编排器首批派发失败(不阻塞): req={} err={}", event.entityId(), e.getMessage());
        }
    }

    /** 调度入口（公开，供事件订阅与后续审批门禁复用）：派发本需求下全部依赖就绪的 TODO 工作单元。 */
    public int dispatchReady(String projectId, String requirementId) {
        List<WorkItemView> items = workItemService.list(projectId, requirementId);
        List<RelationView> edges = relationService.list(projectId, null, null);
        int dispatched = 0;
        for (WorkItemView wi : DispatchPlanner.readyItems(items, edges)) {
            try {
                flowService.startWorkItemSession(projectId, wi.id());
                dispatched++;
                notificationService.emit(new NotificationDraft(NotificationLevel.P1, "flow.dispatched",
                        wi.code() + " 依赖就绪，已自动派发会话",
                        "工作单元「" + wi.title() + "」已自动起会话并转为 IN_PROGRESS",
                        "REQUIREMENT", requirementId, List.of(new ActionDef("view", "查看需求"))));
                log.info("工作单元已自动派发: {} ({})", wi.code(), wi.id());
            } catch (DevMindException e) {
                if (ErrorCode.TOO_MANY_SESSIONS.equals(e.getErrorCode())) {
                    // 并发满：本轮跳过（WI 保持 TODO），下一个 DONE 事件到来时重试
                    log.info("并发会话已满，跳过本轮派发（下次 DONE 事件重试）: req={}", requirementId);
                    break;
                }
                log.warn("自动派发失败(跳过): {} err={}", wi.code(), e.getMessage());
            }
        }
        return dispatched;
    }
}
