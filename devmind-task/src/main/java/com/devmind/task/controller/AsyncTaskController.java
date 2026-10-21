package com.devmind.task.controller;

import com.devmind.common.dto.PageView;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.service.AsyncTaskService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CAP-80 M3 管理面 REST。任务创建走 Java API（FR-10，无 POST /api/async-tasks 创建端点）；
 * POST 控制操作（pause/resume/cancel/trigger）经 SecurityConfig 收敛 ADMIN。
 * 非 admin 的查询自动按本人 actor 过滤；属主外的详情按 404 处理。
 */
@RestController
@RequestMapping("/api/async-tasks")
public class AsyncTaskController {

    private final AsyncTaskService service;

    public AsyncTaskController(AsyncTaskService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<AsyncTaskEntity> list(@RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String actor,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        String actorFilter = service.isAdmin() ? actor : service.actorFilterForCurrentUser();
        Page<AsyncTaskEntity> p = service.query(type, status, keyword, actorFilter, page, size);
        return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    @GetMapping("/{id}")
    public AsyncTaskEntity detail(@PathVariable String id) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        return t;
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        service.pause(id);
        return Map.of("id", id, "status", AsyncTaskEntity.STATUS_PAUSED);
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        service.resume(id);
        return Map.of("id", id, "status", AsyncTaskEntity.STATUS_ACTIVE);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        service.cancel(id);
        return Map.of("id", id, "status", AsyncTaskEntity.STATUS_CANCELLED);
    }

    @PostMapping("/{id}/trigger")
    public Map<String, Object> trigger(@PathVariable String id) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        service.triggerNow(id);
        return Map.of("id", id, "triggered", true);
    }

    @GetMapping("/{id}/runs")
    public PageView<AsyncTaskRunEntity> runs(@PathVariable String id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        AsyncTaskEntity t = service.requireTask(id);
        service.requireVisible(t);
        Page<AsyncTaskRunEntity> p = service.runsOf(id, page, size);
        return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }
}
