package com.devmind.integration.controller;

import com.devmind.integration.dto.JiraSyncConfigRequest;
import com.devmind.integration.dto.JiraSyncConfigView;
import com.devmind.integration.dto.JiraSyncRunView;
import com.devmind.integration.service.JiraSyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-19 项目作用域 Jira 同步端点：配置 CRUD + 手动触发一次同步。
 */
@RestController
@RequestMapping("/api/projects/{pid}/jira-sync")
public class ProjectJiraSyncController {

    private final JiraSyncService service;

    public ProjectJiraSyncController(JiraSyncService service) {
        this.service = service;
    }

    @GetMapping
    public List<JiraSyncConfigView> list(@PathVariable String pid) {
        return service.list(pid);
    }

    @PostMapping
    public JiraSyncConfigView create(@PathVariable String pid, @RequestBody JiraSyncConfigRequest req) {
        return service.create(pid, req);
    }

    @PutMapping("/{configId}")
    public JiraSyncConfigView update(@PathVariable String pid, @PathVariable Long configId,
                                     @RequestBody JiraSyncConfigRequest req) {
        return service.update(pid, configId, req);
    }

    @DeleteMapping("/{configId}")
    public Map<String, Object> delete(@PathVariable String pid, @PathVariable Long configId) {
        service.delete(pid, configId);
        return Map.of("ok", true);
    }

    /** 手动触发一次同步（与定时轮询共用核心逻辑） */
    @PostMapping("/{configId}/run")
    public JiraSyncRunView run(@PathVariable String pid, @PathVariable Long configId) {
        return service.syncNow(pid, configId);
    }
}
