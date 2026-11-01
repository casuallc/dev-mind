package com.devmind.test.controller;

import com.devmind.test.dto.CreatePlanRunRequest;
import com.devmind.test.dto.PlanCaseResult;
import com.devmind.test.dto.TestPlanRequest;
import com.devmind.test.dto.TestPlanRunView;
import com.devmind.test.dto.TestPlanView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.service.TestPlanRunService;
import com.devmind.test.service.TestPlanService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** CAP-104 M2 测试方案与方案运行：CRUD/复制/运行/取消/重跑/下钻/报告。 */
@RestController
@RequestMapping("/api")
public class TestPlanController {

    private final TestPlanService planService;
    private final TestPlanRunService runService;

    public TestPlanController(TestPlanService planService, TestPlanRunService runService) {
        this.planService = planService;
        this.runService = runService;
    }

    // ---------------- 方案 ----------------

    @GetMapping("/projects/{id}/test-plans")
    public List<TestPlanView> list(@PathVariable String id,
                                   @RequestParam(required = false) Boolean templateOnly,
                                   @RequestParam(required = false) String keyword) {
        return planService.list(id, templateOnly, keyword);
    }

    @PostMapping("/projects/{id}/test-plans")
    public TestPlanView create(@PathVariable String id, @RequestBody TestPlanRequest req) {
        return planService.create(id, req);
    }

    @GetMapping("/test-plans/{id}")
    public TestPlanView get(@PathVariable Long id) {
        return planService.get(id);
    }

    @PutMapping("/test-plans/{id}")
    public TestPlanView update(@PathVariable Long id, @RequestBody TestPlanRequest req) {
        return planService.update(id, req);
    }

    @DeleteMapping("/test-plans/{id}")
    public void delete(@PathVariable Long id) {
        planService.delete(id);
    }

    @PostMapping("/test-plans/{id}/copy")
    public TestPlanView copy(@PathVariable Long id, @RequestBody(required = false) TestPlanRequest req) {
        return planService.copy(id, req);
    }

    @PostMapping("/test-plans/{id}/run")
    public TestPlanRunView run(@PathVariable Long id,
                               @RequestBody(required = false) CreatePlanRunRequest req) {
        return runService.create(id, req);
    }

    // ---------------- 方案运行 ----------------

    @GetMapping("/test-plan-runs")
    public List<TestPlanRunView> history(@RequestParam(required = false) String projectId,
                                         @RequestParam(required = false) Long planId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String conclusion) {
        return runService.list(projectId, planId, status, conclusion);
    }

    @GetMapping("/test-plan-runs/{id}")
    public TestPlanRunView getRun(@PathVariable Long id) {
        return runService.get(id);
    }

    @PostMapping("/test-plan-runs/{id}/cancel")
    public TestPlanRunView cancel(@PathVariable Long id) {
        return runService.cancel(id);
    }

    @PostMapping("/test-plan-runs/{id}/rerun")
    public TestPlanRunView rerun(@PathVariable Long id,
                                 @RequestBody(required = false) CreatePlanRunRequest req) {
        return runService.rerun(id, req);
    }

    @GetMapping("/test-plan-runs/{id}/children")
    public List<TestRunView> children(@PathVariable Long id) {
        return runService.children(id);
    }

    @GetMapping("/test-plan-runs/{id}/cases")
    public List<PlanCaseResult> cases(@PathVariable Long id) {
        return runService.caseResults(id);
    }

    @GetMapping(value = "/test-plan-runs/{id}/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String report(@PathVariable Long id) {
        return runService.report(id);
    }

    @GetMapping("/test-plan-runs/{id}/report.json")
    public Map<String, Object> reportJson(@PathVariable Long id) {
        return runService.reportJson(id);
    }
}
