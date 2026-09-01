package com.devmind.openapi.controller;

import com.devmind.build.dto.BuildConfigRequest;
import com.devmind.build.dto.BuildConfigView;
import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.service.BuildConfigService;
import com.devmind.build.service.BuildService;
import com.devmind.deploy.dto.DeployConfigRequest;
import com.devmind.deploy.dto.DeployConfigView;
import com.devmind.deploy.service.DeployConfigService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.BuildStepRequest;
import com.devmind.project.dto.BuildStepView;
import com.devmind.project.dto.EnvironmentRequest;
import com.devmind.project.dto.EnvironmentView;
import com.devmind.project.dto.ProjectRequest;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.ReleaseConfigRequest;
import com.devmind.project.dto.ReleaseConfigView;
import com.devmind.project.dto.ServerRequest;
import com.devmind.project.dto.ServerView;
import com.devmind.serveradapter.dto.TemplateRequest;
import com.devmind.serveradapter.dto.TemplateView;
import com.devmind.serveradapter.service.ScriptTemplateService;
import jakarta.validation.Valid;
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
 * CAP-20 open-api v1（/open-api/v1/**）：对外集成端点，HMAC 签名认证（OpenApiAuthFilter）。
 * 薄层——全部委托现有能力模块的 service，DTO 与 /api/** 管控台面完全同构。
 * 供外部 CI 脚本、第三方客户端、AI 接入助手等使用。
 */
@RestController
@RequestMapping("/open-api/v1")
public class OpenApiV1Controller {

    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final ScriptTemplateService templateService;
    private final BuildConfigService buildConfigService;
    private final BuildService buildService;
    private final DeployConfigService deployConfigService;

    public OpenApiV1Controller(ProjectService projectService,
                               EnvironmentService environmentService,
                               ScriptTemplateService templateService,
                               BuildConfigService buildConfigService,
                               BuildService buildService,
                               DeployConfigService deployConfigService) {
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.templateService = templateService;
        this.buildConfigService = buildConfigService;
        this.buildService = buildService;
        this.deployConfigService = deployConfigService;
    }

    // ---------------- 项目 ----------------

    @GetMapping("/projects")
    public List<ProjectView> listProjects(@RequestParam(required = false) String status) {
        return projectService.list(status);
    }

    @PostMapping("/projects")
    public ProjectView createProject(@Valid @RequestBody ProjectRequest req) {
        return projectService.create(req);
    }

    // ---------------- 服务器（登记到项目下） ----------------

    @PostMapping("/projects/{id}/servers")
    public ServerView addServer(@PathVariable String id, @Valid @RequestBody ServerRequest req) {
        return projectService.addServer(id, req);
    }

    // ---------------- 命令模板白名单 ----------------

    @PostMapping("/script-templates")
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest req) {
        return templateService.create(req);
    }

    // ---------------- 环境 ----------------

    @PostMapping("/projects/{id}/environments")
    public EnvironmentView createEnvironment(@PathVariable String id, @Valid @RequestBody EnvironmentRequest req) {
        return environmentService.create(id, req);
    }

    // ---------------- 构建 ----------------

    /** 构建步骤整表替换（有序） */
    @PutMapping("/projects/{id}/build-steps")
    public List<BuildStepView> replaceBuildSteps(@PathVariable String id,
                                                 @RequestBody List<BuildStepRequest> steps) {
        return projectService.replaceBuildSteps(id, steps);
    }

    @PutMapping("/projects/{id}/build-config")
    public BuildConfigView updateBuildConfig(@PathVariable String id, @RequestBody BuildConfigRequest req) {
        return buildConfigService.update(id, req);
    }

    @PostMapping("/projects/{id}/builds")
    public BuildView triggerBuild(@PathVariable String id, @RequestBody TriggerRequest req) {
        return buildService.trigger(id, req);
    }

    @GetMapping("/builds/{id}")
    public BuildView getBuild(@PathVariable Long id) {
        return buildService.get(id);
    }

    @GetMapping("/builds/{id}/logs")
    public String buildLogs(@PathVariable Long id) {
        return buildService.logs(id);
    }

    // ---------------- 部署 ----------------

    @PutMapping("/projects/{id}/deploy-config")
    public DeployConfigView updateDeployConfig(@PathVariable String id, @RequestBody DeployConfigRequest req) {
        return deployConfigService.update(id, req);
    }

    // ---------------- 发版 ----------------

    @PostMapping("/projects/{id}/release-config")
    public ReleaseConfigView saveReleaseConfig(@PathVariable String id, @RequestBody ReleaseConfigRequest req) {
        return projectService.saveReleaseConfig(id, req);
    }
}
