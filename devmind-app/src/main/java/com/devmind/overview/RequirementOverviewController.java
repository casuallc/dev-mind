package com.devmind.overview;

import com.devmind.overview.dto.RequirementOverviewView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求主线聚合 API（CAP-13）：/api/projects/{projectId}/requirements/{requirementId}/overview
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/overview")
public class RequirementOverviewController {

    private final RequirementOverviewService service;

    public RequirementOverviewController(RequirementOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public RequirementOverviewView overview(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.overview(projectId, requirementId);
    }
}
