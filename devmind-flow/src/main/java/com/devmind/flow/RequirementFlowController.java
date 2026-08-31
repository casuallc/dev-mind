package com.devmind.flow;

import com.devmind.flow.dto.ConfirmSplitRequest;
import com.devmind.flow.dto.SplitDraftView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.session.dto.SessionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 需求流程 REST（CAP-14）：阶段动作 + 拆分草稿/固化 + 工作单元起会话。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class RequirementFlowController {

    private final RequirementFlowService service;

    public RequirementFlowController(RequirementFlowService service) {
        this.service = service;
    }

    /** 开始/重新分析（起分析型会话，需求推进 ANALYZING）。 */
    @PostMapping("/requirements/{requirementId}/flow/analyze")
    public SessionView analyze(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startAnalysis(projectId, requirementId);
    }

    /** 生成方案（创建 DESIGN 型 Work Item 并起会话）。 */
    @PostMapping("/requirements/{requirementId}/flow/design")
    public SessionView design(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startDesign(projectId, requirementId);
    }

    /** AI 拆分（起拆分会话，产出 wi-plan.json）。 */
    @PostMapping("/requirements/{requirementId}/flow/split")
    public SessionView split(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startSplit(projectId, requirementId);
    }

    /** 拆分草稿（解析最近拆分会话的 wi-plan.json，不落库）。 */
    @GetMapping("/requirements/{requirementId}/flow/split-draft")
    public SplitDraftView splitDraft(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.getSplitDraft(projectId, requirementId);
    }

    /** 确认固化（批量建 Work Item + depends_on 边，含环检测）。 */
    @PostMapping("/requirements/{requirementId}/flow/confirm-split")
    public List<WorkItemView> confirmSplit(@PathVariable String projectId,
                                           @PathVariable String requirementId,
                                           @RequestBody ConfirmSplitRequest req) {
        return service.confirmSplit(projectId, requirementId, req);
    }

    /** 工作单元起会话（spec 自动带入 taskSpec）。 */
    @PostMapping("/work-items/{workItemId}/start-session")
    public SessionView startSession(@PathVariable String projectId, @PathVariable String workItemId) {
        return service.startWorkItemSession(projectId, workItemId);
    }
}
