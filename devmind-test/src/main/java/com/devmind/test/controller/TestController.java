package com.devmind.test.controller;

import java.util.List;

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

import com.devmind.test.dto.CreateTestRunRequest;
import com.devmind.test.dto.IssueDraftView;
import com.devmind.test.dto.TestCaseInput;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.dto.TestSuiteCreateRequest;
import com.devmind.test.dto.TestSuiteView;
import com.devmind.test.service.TestRunService;
import com.devmind.test.service.TestSuiteService;

/**
 * CAP-10 REST：套件管理 / OpenAPI 生成（FR-02）/ 套件沉淀（FR-03）/ 运行执行（FR-04）/ 缺陷线索（FR-06）。
 */
@RestController
@RequestMapping("/api")
public class TestController {

    private final TestSuiteService suiteService;
    private final TestRunService runService;

    public TestController(TestSuiteService suiteService, TestRunService runService) {
        this.suiteService = suiteService;
        this.runService = runService;
    }

    // ---------------- 套件 ----------------

    @GetMapping("/projects/{id}/test-suites")
    public List<TestSuiteView> listSuites(@PathVariable String id) {
        return suiteService.list(id);
    }

    @PostMapping("/projects/{id}/test-suites")
    public TestSuiteView createSuite(@PathVariable String id, @RequestBody TestSuiteCreateRequest req) {
        return suiteService.create(id, req);
    }

    /** FR-02 从项目 apiDocSource（OpenAPI）生成 API 套件 */
    @PostMapping("/projects/{id}/test-suites/generate")
    public TestSuiteView generateSuite(@PathVariable String id) {
        return suiteService.generate(id);
    }

    @GetMapping("/test-suites/{id}")
    public TestSuiteView getSuite(@PathVariable Long id) {
        return suiteService.getSuite(id);
    }

    @DeleteMapping("/test-suites/{id}")
    public void deleteSuite(@PathVariable Long id) {
        suiteService.delete(id);
    }

    /** FR-03 套件沉淀为 docs-repo 的 api-suite 文档 */
    @PostMapping("/test-suites/{id}/publish")
    public TestSuiteView publishSuite(@PathVariable Long id) {
        return suiteService.publishToDocs(id);
    }

    /** FR-02 人工编辑用例（整体替换：增删改） */
    @PutMapping("/test-suites/{id}/cases")
    public TestSuiteView saveCases(@PathVariable Long id, @RequestBody List<TestCaseInput> cases) {
        return suiteService.saveCases(id, cases);
    }

    // ---------------- 运行 ----------------

    @PostMapping("/tests/runs")
    public TestRunView createRun(@RequestBody CreateTestRunRequest req) {
        return runService.create(req);
    }

    @GetMapping("/test-runs/{id}")
    public TestRunView getRun(@PathVariable Long id) {
        return runService.get(id);
    }

    @GetMapping("/test-runs")
    public List<TestRunView> history(@RequestParam String projectId,
                                     @RequestParam(required = false) String status) {
        return runService.history(projectId, status);
    }

    @GetMapping(value = "/test-runs/{id}/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String report(@PathVariable Long id) {
        return runService.report(id);
    }

    @GetMapping(value = "/test-runs/{id}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable Long id) {
        return runService.logs(id);
    }

    /** FR-06 失败用例 → 缺陷线索（供流程层一键建缺陷单） */
    @PostMapping("/test-runs/{id}/issues")
    public List<IssueDraftView> issues(@PathVariable Long id) {
        return runService.issues(id);
    }

    @DeleteMapping("/test-runs/{id}")
    public void deleteRun(@PathVariable Long id) {
        runService.delete(id);
    }
}
