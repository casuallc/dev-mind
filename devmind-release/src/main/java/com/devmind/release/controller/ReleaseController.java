package com.devmind.release.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devmind.release.dto.CreateReleaseRequest;
import com.devmind.release.dto.ReleaseView;
import com.devmind.release.service.ReleaseService;

/**
 * CAP-11 REST：新建发版（FR-02 版本管理）、执行/回滚（FR-03/04/06）、历史、全量日志。
 * 发版配置仍走 /projects/{id}/release-config（CAP-02，见 ProjectController）。
 */
@RestController
@RequestMapping("/api")
public class ReleaseController {

    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    @PostMapping("/releases")
    public ReleaseView create(@RequestBody CreateReleaseRequest req) {
        return service.create(req);
    }

    @GetMapping("/releases/{id}")
    public ReleaseView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/releases/{id}/execute")
    public ReleaseView execute(@PathVariable Long id) {
        return service.execute(id);
    }

    @PostMapping("/releases/{id}/rollback")
    public ReleaseView rollback(@PathVariable Long id) {
        return service.rollback(id);
    }

    @GetMapping("/releases")
    public List<ReleaseView> history(@RequestParam String projectId,
                                     @RequestParam(required = false) String status) {
        return service.history(projectId, status);
    }

    @GetMapping("/releases/{id}/logs")
    public String logs(@PathVariable Long id) {
        return service.logs(id);
    }

    @DeleteMapping("/releases/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
