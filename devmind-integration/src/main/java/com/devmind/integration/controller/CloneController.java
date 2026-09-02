package com.devmind.integration.controller;

import com.devmind.integration.service.RepoCloneService;
import com.devmind.project.dto.RepoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-23 仓库克隆端点（与 ProjectController 同 /api/projects 前缀，不同子路径）。
 * 实时日志走 WS /ws/repo-clones/clone-&lt;repoId&gt;。
 */
@RestController
@RequestMapping("/api/projects")
public class CloneController {

    private final RepoCloneService cloneService;

    public CloneController(RepoCloneService cloneService) {
        this.cloneService = cloneService;
    }

    /** 触发/重试单库克隆（CLONING 中返回 409） */
    @PostMapping("/{id}/repos/{repoId}/clone")
    public RepoView clone(@PathVariable String id, @PathVariable Long repoId) {
        return cloneService.requestClone(id, repoId);
    }

    /** 重试项目内全部 FAILED 库 */
    @PostMapping("/{id}/clone/retry")
    public List<RepoView> retry(@PathVariable String id) {
        return cloneService.retryFailed(id);
    }

    /** 克隆日志回放（非实时） */
    @GetMapping("/{id}/repos/{repoId}/clone/logs")
    public Map<String, String> logs(@PathVariable String id, @PathVariable Long repoId) {
        return Map.of("logs", cloneService.cloneLogs(id, repoId));
    }
}
