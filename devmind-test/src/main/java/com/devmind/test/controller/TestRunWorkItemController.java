package com.devmind.test.controller;

import com.devmind.project.dto.WorkItemView;
import com.devmind.test.dto.TestRunWorkItemRequest;
import com.devmind.test.service.TestRunWorkItemService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-104 FR-17 失败用例一键创建工作单元（带断言差异与证据链接）。 */
@RestController
@RequestMapping("/api")
public class TestRunWorkItemController {

    private final TestRunWorkItemService service;

    public TestRunWorkItemController(TestRunWorkItemService service) {
        this.service = service;
    }

    @PostMapping("/test-runs/{id}/work-items")
    public WorkItemView create(@PathVariable Long id, @RequestBody TestRunWorkItemRequest req) {
        return service.create(id, req);
    }
}
