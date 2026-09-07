package com.devmind.integration.controller;

import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.JiraTransitionRequest;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.dto.JiraWorklogRequest;
import com.devmind.integration.dto.JiraWorklogResultView;
import com.devmind.integration.service.JiraIssueActionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CAP-19 项目作用域 Jira issue 操作端点：FR-08 工作流转换（平台侧状态回写）、
 * CAP-27 工时登记、FR-09 附件内容代理（描述图片按需拉取）。
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

    /** 登记工时（CAP-27）：写 Jira worklog 并刷新 timeSpent；本地需求状态不动 */
    @PostMapping("/worklog")
    public JiraWorklogResultView logWork(@PathVariable String pid, @PathVariable String rid,
                                         @RequestBody JiraWorklogRequest req) {
        return service.logWork(pid, rid, req.seconds(), req.comment());
    }

    /**
     * CAP-19 FR-09：issue 附件内容代理（描述 wiki 图片 !name.png! 的按需数据源）。
     * inline + 短缓存；文件名走 query 参数（避开中文/空格进路径段的编码坑）。
     * 图片经 <img src="...?access_token="> 访问（JwtAuthFilter GET 回退），无需自定义头。
     */
    @GetMapping("/attachments")
    public ResponseEntity<byte[]> attachment(@PathVariable String pid, @PathVariable String rid,
                                             @RequestParam("name") String name) {
        IntegrationConnector.IssueAttachment attachment = service.loadAttachment(pid, rid, name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        attachment.mimeType() != null && !attachment.mimeType().isBlank()
                                ? attachment.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(attachment.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .body(attachment.content());
    }
}
