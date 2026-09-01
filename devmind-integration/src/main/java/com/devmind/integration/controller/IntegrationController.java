package com.devmind.integration.controller;

import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.IntegrationRequest;
import com.devmind.integration.dto.IntegrationView;
import com.devmind.integration.service.IntegrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-18 Integration 平台实例管理（FR-01/02）。写方法在 SecurityConfig 收紧为仅 ADMIN；
 * 凭据不明文回显（视图仅 hasToken）。
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IntegrationView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public IntegrationView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public IntegrationView create(@RequestBody IntegrationRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public IntegrationView update(@PathVariable Long id, @RequestBody IntegrationRequest req) {
        return service.update(id, req);
    }

    @PutMapping("/{id}/status")
    public IntegrationView changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.changeStatus(id, body.get("status"));
    }

    /** FR-02 连接测试 */
    @PostMapping("/{id}/test")
    public IntegrationConnector.TestResult test(@PathVariable Long id) {
        return service.testConnection(id);
    }

    /** FR-03 绑定辅助：列出 token 可见的平台项目 */
    @GetMapping("/{id}/projects")
    public List<IntegrationConnector.ExternalProject> projects(@PathVariable Long id) {
        return service.listExternalProjects(id);
    }
}
