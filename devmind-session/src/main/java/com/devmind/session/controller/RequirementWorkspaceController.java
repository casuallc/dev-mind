package com.devmind.session.controller;

import com.devmind.common.agent.FinalizeResult;
import com.devmind.session.dto.FinalizeRequest;
import com.devmind.session.service.SessionManagerService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-51 FR-04 需求级工作区 REST API。收口从「会话级动作」上移为「需求级动作」——
 * 需求是工作区的归属单位，入口挂在需求详情页（前端 M2）。
 *
 * <p>路径沿用 {@code RequirementController} 的 {@code /api/projects/{projectId}/requirements}
 * 前缀（同域资源同一前缀），但实现落在 devmind-session：收口要下发 workspace_finalize 帧并
 * 按 session_repos 快照定位工作树，project 模块不反向依赖 session。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/workspace")
public class RequirementWorkspaceController {

    private final SessionManagerService service;

    public RequirementWorkspaceController(SessionManagerService service) {
        this.service = service;
    }

    /**
     * 收口合并到基线：取该需求最近的 workspace OPEN 会话 → 按其仓库快照合并需求分支到基线并 push。
     * CAP-51 起<b>保留</b>工作树与分支（需求可能继续开发），成功后需求 workspace_state=FINALIZED。
     * body {@code {discardChanges}} 缺省 = 脏工作区直接失败保留现场。
     */
    @PostMapping("/finalize")
    public FinalizeResult finalize(@PathVariable String projectId, @PathVariable String requirementId,
                                   @RequestBody(required = false) FinalizeRequest req) {
        return service.finalizeRequirementWorkspace(projectId, requirementId,
                req != null && req.effectiveDiscardChanges());
    }
}
