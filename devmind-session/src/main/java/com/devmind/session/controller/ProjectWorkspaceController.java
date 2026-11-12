package com.devmind.session.controller;

import com.devmind.session.dto.WorkspaceRow;
import com.devmind.session.service.RemoteBranchAdminService;
import com.devmind.session.service.WorkspaceAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作区管理 tab（会话页「工作区」视图）：项目内 runner 固定工作区聚合列表 +
 * 远端 feature 分支列举/删除。收口/清理动作复用会话级端点
 * （POST /api/sessions/{id}/finalize、DELETE /api/sessions/{id}/workspace）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/workspaces")
public class ProjectWorkspaceController {

    private final WorkspaceAdminService workspaceAdminService;
    private final RemoteBranchAdminService remoteBranchAdminService;

    public ProjectWorkspaceController(WorkspaceAdminService workspaceAdminService,
                                      RemoteBranchAdminService remoteBranchAdminService) {
        this.workspaceAdminService = workspaceAdminService;
        this.remoteBranchAdminService = remoteBranchAdminService;
    }

    @GetMapping
    public List<WorkspaceRow> list(@PathVariable String projectId) {
        return workspaceAdminService.list(projectId);
    }

    @GetMapping("/remote-branches")
    public RemoteBranchAdminService.RemoteBranchList remoteBranches(@PathVariable String projectId) {
        return remoteBranchAdminService.list(projectId);
    }

    /** 删除远端 feature 分支（仅 feature/ 前缀；幂等）。 */
    @PostMapping("/remote-branches/delete")
    public void deleteRemoteBranch(@PathVariable String projectId,
                                   @RequestBody DeleteBranchRequest req) {
        remoteBranchAdminService.delete(projectId,
                req != null ? req.repoName() : null, req != null ? req.branch() : null);
    }

    public record DeleteBranchRequest(String repoName, String branch) {
    }
}
