package com.devmind.test.controller;

import com.devmind.test.dto.PrecheckReport;
import com.devmind.test.service.EnvironmentPrecheckService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAP-104 FR-06 环境预检：NODE_ONLINE / EXEC_PROTOCOL / HTTP / COMMAND / VERSION_MATCH。 */
@RestController
@RequestMapping("/api/projects/{projectId}/environments")
public class TestPrecheckController {

    private final EnvironmentPrecheckService service;

    public TestPrecheckController(EnvironmentPrecheckService service) {
        this.service = service;
    }

    @PostMapping("/{envId}/precheck")
    public PrecheckReport precheck(@PathVariable String projectId, @PathVariable Long envId,
                                   @RequestParam(required = false) String nodeId) {
        return service.run(projectId, envId, nodeId);
    }
}
