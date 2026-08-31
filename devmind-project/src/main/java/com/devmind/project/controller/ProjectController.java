package com.devmind.project.controller;

import com.devmind.project.ProjectService;
import com.devmind.project.dto.BuildStepRequest;
import com.devmind.project.dto.BuildStepView;
import com.devmind.project.dto.ContextSummaryView;
import com.devmind.project.dto.LockRequest;
import com.devmind.project.dto.ProjectLockView;
import com.devmind.project.dto.ProjectRequest;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.ReleaseConfigRequest;
import com.devmind.project.dto.ReleaseConfigView;
import com.devmind.project.dto.RepoRequest;
import com.devmind.project.dto.RepoView;
import com.devmind.project.dto.ServerRequest;
import com.devmind.project.dto.ServerView;
import com.devmind.project.dto.SummaryRequest;
import com.devmind.project.dto.WorktreeView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-02 项目 REST API。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    // ---------------- 项目 CRUD ----------------

    @GetMapping
    public List<ProjectView> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @PostMapping
    public ProjectView create(@Valid @RequestBody ProjectRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public ProjectView update(@PathVariable String id, @Valid @RequestBody ProjectRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    // ---------------- 项目仓库（P0-4 多库模型） ----------------

    @GetMapping("/{id}/repos")
    public List<RepoView> repos(@PathVariable String id) {
        return service.listRepos(id);
    }

    @PostMapping("/{id}/repos")
    public RepoView addRepo(@PathVariable String id, @Valid @RequestBody RepoRequest req) {
        return service.addRepo(id, req);
    }

    @PutMapping("/{id}/repos/{repoId}")
    public RepoView updateRepo(@PathVariable String id, @PathVariable Long repoId,
                               @Valid @RequestBody RepoRequest req) {
        return service.updateRepo(id, repoId, req);
    }

    @DeleteMapping("/{id}/repos/{repoId}")
    public void deleteRepo(@PathVariable String id, @PathVariable Long repoId) {
        service.deleteRepo(id, repoId);
    }

    /** 设为主库（项目内唯一，同步 projects.path 镜像） */
    @PostMapping("/{id}/repos/{repoId}/primary")
    public RepoView setPrimaryRepo(@PathVariable String id, @PathVariable Long repoId) {
        return service.setPrimaryRepo(id, repoId);
    }

    // ---------------- 上下文摘要 ----------------

    @GetMapping("/{id}/summary")
    public ContextSummaryView summary(@PathVariable String id) {
        return service.getSummary(id);
    }

    @PostMapping("/{id}/summary/refresh")
    public ContextSummaryView refreshSummary(@PathVariable String id) {
        return service.refreshSummary(id);
    }

    @PutMapping("/{id}/summary")
    public ContextSummaryView updateSummary(@PathVariable String id, @RequestBody SummaryRequest req) {
        return service.updateSummary(id, req.text());
    }

    // ---------------- 服务器 ----------------

    @GetMapping("/{id}/servers")
    public List<ServerView> servers(@PathVariable String id) {
        return service.listServers(id);
    }

    @PostMapping("/{id}/servers")
    public ServerView addServer(@PathVariable String id, @Valid @RequestBody ServerRequest req) {
        return service.addServer(id, req);
    }

    @PutMapping("/{id}/servers/{serverId}")
    public ServerView updateServer(@PathVariable String id, @PathVariable Long serverId,
                                   @Valid @RequestBody ServerRequest req) {
        return service.updateServer(id, serverId, req);
    }

    @DeleteMapping("/{id}/servers/{serverId}")
    public void deleteServer(@PathVariable String id, @PathVariable Long serverId) {
        service.deleteServer(id, serverId);
    }

    // ---------------- 构建配置 ----------------

    @GetMapping("/{id}/build-steps")
    public List<BuildStepView> buildSteps(@PathVariable String id) {
        return service.listBuildSteps(id);
    }

    @PostMapping("/{id}/build-steps")
    public BuildStepView addBuildStep(@PathVariable String id, @Valid @RequestBody BuildStepRequest req) {
        return service.addBuildStep(id, req);
    }

    @PutMapping("/{id}/build-steps/{stepId}")
    public BuildStepView updateBuildStep(@PathVariable String id, @PathVariable Long stepId,
                                         @Valid @RequestBody BuildStepRequest req) {
        return service.updateBuildStep(id, stepId, req);
    }

    @DeleteMapping("/{id}/build-steps/{stepId}")
    public void deleteBuildStep(@PathVariable String id, @PathVariable Long stepId) {
        service.deleteBuildStep(id, stepId);
    }

    /** 整表替换（排序后一次提交）。 */
    @PutMapping("/{id}/build-steps")
    public List<BuildStepView> replaceBuildSteps(@PathVariable String id,
                                                 @RequestBody List<BuildStepRequest> steps) {
        return service.replaceBuildSteps(id, steps);
    }

    // ---------------- 发版配置 ----------------

    @GetMapping("/{id}/release-config")
    public ReleaseConfigView releaseConfig(@PathVariable String id) {
        return service.getReleaseConfig(id);
    }

    @PostMapping("/{id}/release-config")
    public ReleaseConfigView saveReleaseConfig(@PathVariable String id,
                                               @RequestBody ReleaseConfigRequest req) {
        return service.saveReleaseConfig(id, req);
    }

    // ---------------- 锁定 ----------------

    @GetMapping("/{id}/lock")
    public ProjectLockView lock(@PathVariable String id) {
        return service.getLock(id);
    }

    @PutMapping("/{id}/lock")
    public ProjectLockView updateLock(@PathVariable String id, @RequestBody LockRequest req) {
        return service.updateLock(id, req);
    }

    @PostMapping("/{id}/lock/claim")
    public ProjectLockView claim(@PathVariable String id) {
        return service.claimWrite(id);
    }

    @PostMapping("/{id}/lock/release")
    public ProjectLockView release(@PathVariable String id) {
        return service.releaseWrite(id);
    }

    // ---------------- worktree ----------------

    @GetMapping("/{id}/worktrees")
    public List<WorktreeView> worktrees(@PathVariable String id) {
        return service.listWorktrees(id);
    }
}
