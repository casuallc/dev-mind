package com.devmind.session.controller;

import com.devmind.common.agent.ProjectAssetsProvider;
import com.devmind.project.ProjectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-33 FR-06 项目「上下文」页签：聚合各资产模块的 {@link ProjectAssetsProvider}
 * （knowledge/docs/skill，ObjectProvider 探测注入不反向依赖），按 kind 分组出只读清单。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/context-assets")
public class ProjectContextAssetsController {

    private final ProjectService projectService;
    private final ObjectProvider<ProjectAssetsProvider> providers;

    public ProjectContextAssetsController(ProjectService projectService,
                                          ObjectProvider<ProjectAssetsProvider> providers) {
        this.projectService = projectService;
        this.providers = providers;
    }

    public record AssetGroup(String kind, List<ProjectAssetsProvider.ProjectAssetItem> items) {
    }

    @GetMapping
    public List<AssetGroup> list(@PathVariable String projectId) {
        projectService.requireProject(projectId); // 项目不存在 404
        return providers.orderedStream()
                .map(p -> new AssetGroup(p.kind(), p.listByProject(projectId)))
                .toList();
    }
}
