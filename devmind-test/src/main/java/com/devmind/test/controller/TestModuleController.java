package com.devmind.test.controller;

import com.devmind.test.dto.TestModuleRequest;
import com.devmind.test.dto.TestModuleView;
import com.devmind.test.service.TestModuleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CAP-104 FR-01 功能模块（项目 → 模块 → 套件 → 用例）。 */
@RestController
@RequestMapping("/api")
public class TestModuleController {

    private final TestModuleService service;

    public TestModuleController(TestModuleService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/test-modules")
    public List<TestModuleView> list(@PathVariable String projectId) {
        return service.list(projectId);
    }

    @PostMapping("/projects/{projectId}/test-modules")
    public TestModuleView create(@PathVariable String projectId, @RequestBody TestModuleRequest req) {
        return service.create(projectId, req);
    }

    @PutMapping("/test-modules/{id}")
    public TestModuleView update(@PathVariable Long id, @RequestBody TestModuleRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/test-modules/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
