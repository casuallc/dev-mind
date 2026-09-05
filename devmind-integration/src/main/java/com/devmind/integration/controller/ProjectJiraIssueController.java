package com.devmind.integration.controller;

import com.devmind.integration.dto.JiraTransitionRequest;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.service.JiraIssueActionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-19 FR-08 项目作用域 Jira issue 操作端点：工作流转换清单 + 执行转换（平台侧状态回写）。
 */
@RestController
@RequestMapping("/api/projects/{pid}/requirements/{rid}/jira")
public class ProjectJiraIssueController {

    private final JiraIssueActionService service;

    public ProjectJiraIssueController(JiraIssueActionService service) {
        this.service = service;
    }

    /** 需求关联 issue 当前可用的工作流转换（详情页「Jira 操作」下拉数据源） */
    @GetMapping("/transitions")
    public List<JiraTransitionView> transitions(@PathVariable String pid, @PathVariable String rid) {
        return service.listTransitions(pid, rid);
    }

    /** 执行一次工作流转换（只回写远端并刷新托管字段，本地需求状态不动） */
    @PostMapping("/transitions")
    public JiraTransitionResultView transit(@PathVariable String pid, @PathVariable String rid,
                                            @RequestBody JiraTransitionRequest req) {
        return service.transit(pid, rid, req.transitionId());
    }
}
