package com.devmind.build.controller;

import com.devmind.build.dto.BuildConfigRequest;
import com.devmind.build.dto.BuildConfigView;
import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.service.BuildConfigService;
import com.devmind.build.service.BuildService;
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
 * CAP-08 REST：构建配置（FR-02）、触发/详情/历史（FR-07）、全量日志（FR-05 兜底）。
 */
@RestController
@RequestMapping("/api")
public class BuildController {

    private final BuildConfigService configService;
    private final BuildService buildService;

    public BuildController(BuildConfigService configService, BuildService buildService) {
        this.configService = configService;
        this.buildService = buildService;
    }

    // ---------------- 构建配置（FR-02） ----------------

    @GetMapping("/projects/{id}/build-config")
    public BuildConfigView getConfig(@PathVariable String id) {
        return configService.get(id);
    }

    @PutMapping("/projects/{id}/build-config")
    public BuildConfigView updateConfig(@PathVariable String id, @RequestBody BuildConfigRequest req) {
        return configService.update(id, req);
    }

    // ---------------- 构建（FR-03/04/06/07） ----------------

    @PostMapping("/projects/{id}/builds")
    public BuildView trigger(@PathVariable String id, @RequestBody TriggerRequest req) {
        return buildService.trigger(id, req);
    }

    @GetMapping("/builds/{id}")
    public BuildView get(@PathVariable Long id) {
        return buildService.get(id);
    }

    @GetMapping("/builds")
    public List<BuildView> history(@RequestParam String projectId, @RequestParam(required = false) String status) {
        return buildService.history(projectId, status);
    }

    @GetMapping("/builds/{id}/logs")
    public String logs(@PathVariable Long id) {
        return buildService.logs(id);
    }

    @DeleteMapping("/builds/{id}")
    public void delete(@PathVariable Long id) {
        buildService.delete(id);
    }
}
