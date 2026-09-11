package com.devmind.flow;

import com.devmind.session.dto.SessionView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求流程 REST（CAP-14/CAP-38）：阶段动作（分析/方案/拆分）+ 阶段跳过 + 工作单元起会话。
 * CAP-38 起拆分产出直接固化，草稿/确认端点已删除。
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

    /** 生成方案（创建 DESIGN 型 Work Item 并起会话；产出后自动拆分）。 */
    @PostMapping("/requirements/{requirementId}/flow/design")
    public SessionView design(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startDesign(projectId, requirementId);
    }

    /** AI 拆分（手动路径，起拆分会话，产出 wi-plan.json 自动固化）。 */
    @PostMapping("/requirements/{requirementId}/flow/split")
    public SessionView split(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.startSplit(projectId, requirementId);
    }

    /** CAP-38 FR-01 阶段跳过（幂等）：stage = analysis | design。 */
    @PostMapping("/requirements/{requirementId}/flow/skip")
    public void skip(@PathVariable String projectId, @PathVariable String requirementId,
                     @RequestBody SkipStageRequest req) {
        service.skipStage(projectId, requirementId, req.stage());
    }

    /** 工作单元起会话（spec 自动带入 taskSpec）。 */
    @PostMapping("/work-items/{workItemId}/start-session")
    public SessionView startSession(@PathVariable String projectId, @PathVariable String workItemId) {
        return service.startWorkItemSession(projectId, workItemId);
    }

    /** 阶段跳过请求体。 */
    public record SkipStageRequest(String stage) {
    }
}
