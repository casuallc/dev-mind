package com.devmind.test.controller;

import com.devmind.test.dto.CaseReviewRequest;
import com.devmind.test.dto.CaseRunRequest;
import com.devmind.test.dto.CaseSaveResult;
import com.devmind.test.dto.TestCaseSaveRequest;
import com.devmind.test.dto.TestCaseVersionView;
import com.devmind.test.dto.TestCaseView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.service.TestCaseService;
import com.devmind.test.service.TestRunService;
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

/** CAP-104 FR-02/03/07/11 用例库：CaseSpec CRUD、不可变版本、评审、检索、单用例运行。 */
@RestController
@RequestMapping("/api")
public class TestCaseController {

    private final TestCaseService caseService;
    private final TestRunService runService;

    public TestCaseController(TestCaseService caseService, TestRunService runService) {
        this.caseService = caseService;
        this.runService = runService;
    }

    @GetMapping("/test-cases")
    public List<TestCaseView> search(@RequestParam String projectId,
                                     @RequestParam(required = false) Long suiteId,
                                     @RequestParam(required = false) Long moduleId,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String reviewStatus,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String tags) {
        return caseService.search(projectId, suiteId, moduleId, type, reviewStatus, keyword, tags);
    }

    @GetMapping("/test-cases/{id}")
    public TestCaseView get(@PathVariable Long id) {
        return caseService.get(id);
    }

    @PostMapping("/test-suites/{suiteId}/test-cases")
    public CaseSaveResult create(@PathVariable Long suiteId, @RequestBody TestCaseSaveRequest req) {
        return caseService.create(suiteId, req);
    }

    @PutMapping("/test-cases/{id}")
    public CaseSaveResult update(@PathVariable Long id, @RequestBody TestCaseSaveRequest req) {
        return caseService.update(id, req);
    }

    @DeleteMapping("/test-cases/{id}")
    public void delete(@PathVariable Long id) {
        caseService.delete(id);
    }

    @GetMapping("/test-cases/{id}/versions")
    public List<TestCaseVersionView> versions(@PathVariable Long id) {
        return caseService.versions(id);
    }

    @PostMapping("/test-cases/{id}/review")
    public TestCaseView review(@PathVariable Long id, @RequestBody CaseReviewRequest req) {
        return caseService.review(id, req);
    }

    @PostMapping("/test-cases/{id}/run")
    public TestRunView run(@PathVariable Long id, @RequestBody(required = false) CaseRunRequest req) {
        return runService.createCaseRun(id, req);
    }
}
