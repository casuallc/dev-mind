package com.devmind.deploy.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devmind.deploy.dto.CreateDeploymentRequest;
import com.devmind.deploy.dto.DeployConfigRequest;
import com.devmind.deploy.dto.DeployConfigView;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.service.DeployConfigService;
import com.devmind.deploy.service.DeploymentService;

/**
 * CAP-09 REST：部署计划配置（FR-01）、创建/详情/执行/确认/回滚（FR-03/04/05/07）、历史、全量日志。
 */
@RestController
@RequestMapping("/api")
public class DeployController {

    private final DeployConfigService configService;
    private final DeploymentService deploymentService;

    public DeployController(DeployConfigService configService, DeploymentService deploymentService) {
        this.configService = configService;
        this.deploymentService = deploymentService;
    }

    // ---------------- 部署计划配置（FR-01） ----------------

    @GetMapping("/projects/{id}/deploy-config")
    public DeployConfigView getConfig(@PathVariable String id) {
        return configService.get(id);
    }

    @PutMapping("/projects/{id}/deploy-config")
    public DeployConfigView updateConfig(@PathVariable String id, @RequestBody DeployConfigRequest req) {
        return configService.update(id, req);
    }

    @DeleteMapping("/projects/{id}/deploy-config")
    public void deleteConfig(@PathVariable String id) {
        configService.delete(id);
    }

    // ---------------- 部署单（FR-03/04/05/07） ----------------

    @PostMapping("/deployments")
    public DeploymentView create(@RequestBody CreateDeploymentRequest req) {
        return deploymentService.create(req);
    }

    @GetMapping("/deployments/{id}")
    public DeploymentView get(@PathVariable Long id) {
        return deploymentService.get(id);
    }

    @PostMapping("/deployments/{id}/execute")
    public DeploymentView execute(@PathVariable Long id) {
        return deploymentService.execute(id);
    }

    @PostMapping("/deployments/{id}/confirm")
    public DeploymentView confirm(@PathVariable Long id) {
        return deploymentService.confirm(id);
    }

    @PostMapping("/deployments/{id}/rollback")
    public DeploymentView rollback(@PathVariable Long id) {
        return deploymentService.rollback(id);
    }

    @GetMapping("/deployments")
    public List<DeploymentView> history(@RequestParam String projectId,
                                        @RequestParam(required = false) String status) {
        return deploymentService.history(projectId, status);
    }

    @GetMapping("/deployments/{id}/logs")
    public String logs(@PathVariable Long id) {
        return deploymentService.logs(id);
    }

    @DeleteMapping("/deployments/{id}")
    public void delete(@PathVariable Long id) {
        deploymentService.delete(id);
    }
}
