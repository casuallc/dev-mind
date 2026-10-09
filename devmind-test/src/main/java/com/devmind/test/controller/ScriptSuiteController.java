package com.devmind.test.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devmind.test.dto.ScriptSuiteRequest;
import com.devmind.test.dto.ScriptSuiteRunRequest;
import com.devmind.test.dto.ScriptSuiteView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.service.ScriptSuiteService;
import com.devmind.test.service.TestRunService;

/**
 * CAP-69 脚本套件 REST（强制绑定项目）：CRUD（env 视图层脱敏）+ 触发运行。
 * 鉴权见 SecurityConfig：写操作 ADMIN，GET 与 /run 登录即可。
 */
@RestController
@RequestMapping("/api/script-suites")
public class ScriptSuiteController {

    private final ScriptSuiteService suiteService;
    private final TestRunService runService;

    public ScriptSuiteController(ScriptSuiteService suiteService, TestRunService runService) {
        this.suiteService = suiteService;
        this.runService = runService;
    }

    @GetMapping
    public List<ScriptSuiteView> list(@RequestParam String projectId) {
        return suiteService.list(projectId);
    }

    @PostMapping
    public ScriptSuiteView create(@RequestBody ScriptSuiteRequest req) {
        return suiteService.create(req);
    }

    @GetMapping("/{id}")
    public ScriptSuiteView get(@PathVariable Long id) {
        return suiteService.get(id);
    }

    @PutMapping("/{id}")
    public ScriptSuiteView update(@PathVariable Long id, @RequestBody ScriptSuiteRequest req) {
        return suiteService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        suiteService.delete(id);
    }

    /** FR-02 触发运行（body 全可选：agentNodeId/env 覆盖/command 覆盖） */
    @PostMapping("/{id}/run")
    public TestRunView run(@PathVariable Long id, @RequestBody(required = false) ScriptSuiteRunRequest req) {
        return runService.createScriptRun(id, req);
    }
}
