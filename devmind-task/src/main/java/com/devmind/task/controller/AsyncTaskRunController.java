package com.devmind.task.controller;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.service.AsyncTaskService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-80 run 详情/日志（独立前缀 /api/async-task-runs，避免与 /{id}/runs 撞路径）。
 * 属主校验经任务归属（非 admin 看他人 run 404）。
 */
@RestController
@RequestMapping("/api/async-task-runs")
public class AsyncTaskRunController {

    private final AsyncTaskService service;

    public AsyncTaskRunController(AsyncTaskService service) {
        this.service = service;
    }

    @GetMapping("/{runId}")
    public AsyncTaskRunEntity detail(@PathVariable String runId) {
        AsyncTaskRunEntity run = service.requireRun(runId);
        service.requireVisible(service.requireTask(run.getTaskId()));
        return run;
    }

    /** 执行记录横查（管理页「执行记录」视图）：按状态/任务类型过滤。 */
    @GetMapping
    public PageView<AsyncTaskRunEntity> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String taskId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        if (taskId != null && !taskId.isBlank()) {
            service.requireVisible(service.requireTask(taskId));
            Page<AsyncTaskRunEntity> p = service.runsOf(taskId, page, size);
            return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
        if (!service.isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "横查执行记录仅管理员可用");
        }
        Page<AsyncTaskRunEntity> p = service.queryRuns(status, page, size);
        return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }
}
