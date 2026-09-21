package com.devmind.flow;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作单元编排器（CAP-15；<b>自动派发已于 CAP-52 停用</b>）。
 *
 * <p>原语义：依赖就绪的 WI 自动起会话（触发点 = WI 翻 DONE / 拆分固化）。CAP-52 把执行阶段
 * 改成「一个需求一个开发会话」——整个清单交给一个会话一次做完，因此逐 WI 派发会让每个 WI
 * 重新起进程、重新理解需求、重新注入上下文，正是要消灭的请求数与 token 开销。</p>
 *
 * <p>保留理由：{@link #dispatchReady} 与 {@link DispatchPlanner} 是「按 WI 分派」这条路的完整
 * 实现，人工/后续能力可直接复用（方法仍公开、仍可用）。停用的只是两条自动触发路径：
 * 事件订阅已移除，`workitem.status.changed` 与 `flow.split.confirmed` 不再自动派生会话
 * （`flow.split.confirmed` 事件本身仍由 {@link RequirementFlowService} 发布，作为领域留痕）。</p>
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

    /**
     * 派发本需求下全部依赖就绪的 TODO 工作单元（每个 WI 一个会话）。
     *
     * <p>CAP-52 起**无自动调用方**（保留为手动/复用的入口）：需要按 WI 分派时调用本方法，
     * 或在工作单元行内点「起会话」单个派发。</p>
     */
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
                        "REQUIREMENT", requirementId, projectId, List.of(new ActionDef("view", "查看需求"))));
                log.info("工作单元已自动派发: {} ({})", wi.code(), wi.id());
            } catch (DevMindException e) {
                if (ErrorCode.TOO_MANY_SESSIONS.equals(e.getErrorCode())) {
                    // 并发满：本轮跳过（WI 保持 TODO），留待下次显式派发
                    log.info("并发会话已满，跳过本轮派发: req={}", requirementId);
                    break;
                }
                log.warn("自动派发失败(跳过): {} err={}", wi.code(), e.getMessage());
            }
        }
        return dispatched;
    }
}
