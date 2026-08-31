package com.devmind.overview;

import com.devmind.overview.dto.TaskOverviewView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务主线聚合 API（P0-6 步骤 4）：/api/projects/{projectId}/tasks/{taskId}/overview
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tasks/{taskId}/overview")
public class TaskOverviewController {

    private final TaskOverviewService service;

    public TaskOverviewController(TaskOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public TaskOverviewView overview(@PathVariable String projectId, @PathVariable String taskId) {
        return service.overview(projectId, taskId);
    }
}
