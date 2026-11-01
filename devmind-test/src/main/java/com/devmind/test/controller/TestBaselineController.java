package com.devmind.test.controller;

import com.devmind.test.dto.BaselineComparison;
import com.devmind.test.dto.CreateBaselineRequest;
import com.devmind.test.dto.TestBaselineView;
import com.devmind.test.service.TestBaselineService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CAP-104 FR-16 基线：从方案运行创建、列表、删除与回归对比。 */
@RestController
@RequestMapping("/api")
public class TestBaselineController {

    private final TestBaselineService service;

    public TestBaselineController(TestBaselineService service) {
        this.service = service;
    }

    @GetMapping("/projects/{id}/test-baselines")
    public List<TestBaselineView> list(@PathVariable String id,
                                       @RequestParam(required = false) Long planId) {
        return service.list(id, planId);
    }

    @PostMapping("/test-plan-runs/{id}/baselines")
    public TestBaselineView create(@PathVariable Long id, @RequestBody CreateBaselineRequest req) {
        return service.create(id, req == null ? null : req.name(),
                req == null ? null : req.productVersion());
    }

    @GetMapping("/test-baselines/{id}")
    public TestBaselineView get(@PathVariable Long id) {
        return service.get(id);
    }

    @DeleteMapping("/test-baselines/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/test-baselines/{id}/compare")
    public BaselineComparison compare(@PathVariable Long id, @RequestParam Long planRunId) {
        return service.compare(id, planRunId);
    }
}
