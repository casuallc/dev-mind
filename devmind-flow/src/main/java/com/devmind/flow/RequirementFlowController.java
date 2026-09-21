package com.devmind.flow;

import com.devmind.session.dto.SessionView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求流程 REST（CAP-14/CAP-38/CAP-52）：CAP-52 起只有两个入口——「开启 AI 规划」（一个会话产出
 * 分析+方案+工作单元，产出后自动接开发会话）与「重新开发」（按清单起需求级开发会话）。
 * CAP-52 之前的阶段入口 analyze/design/split/skip 已删除（FR-07 入口收敛）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class RequirementFlowController {

    private final RequirementFlowService service;

    public RequirementFlowController(RequirementFlowService service) {
        this.service = service;
    }

    /** CAP-52 FR-01「开启 AI 规划」：一个会话产出分析 + 方案 + 工作单元，产出后自动起开发会话。 */
    @PostMapping("/requirements/{requirementId}/flow/plan")
    public SessionView plan(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startPlan(projectId, requirementId);
    }

    /** CAP-52 FR-04「重新开发」：按已固化清单重起需求级开发会话。 */
    @PostMapping("/requirements/{requirementId}/flow/dev")
    public SessionView dev(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startDev(projectId, requirementId);
    }

    /** 工作单元起会话（人工逃生通道：单个 WI 一个会话，spec 自动带入 taskSpec）。 */
    @PostMapping("/work-items/{workItemId}/start-session")
    public SessionView startSession(@PathVariable String projectId, @PathVariable String workItemId) {
        return service.startWorkItemSession(projectId, workItemId);
    }
}
