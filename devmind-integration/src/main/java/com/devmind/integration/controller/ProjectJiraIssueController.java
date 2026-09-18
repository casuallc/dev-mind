package com.devmind.integration.controller;

import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.JiraAssignableUserView;
import com.devmind.integration.dto.JiraCreateFieldsView;
import com.devmind.integration.dto.JiraPushOptionsView;
import com.devmind.integration.dto.JiraPushRequest;
import com.devmind.integration.dto.JiraPushResultView;
import com.devmind.integration.dto.JiraPushTargetsView;
import com.devmind.integration.dto.JiraTransitionRequest;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.dto.JiraWorklogRequest;
import com.devmind.integration.dto.JiraWorklogResultView;
import com.devmind.integration.service.JiraIssueActionService;
import com.devmind.integration.service.JiraPushService;
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
 * 项目作用域 Jira issue 端点（全部挂在需求上）：
 * CAP-19 FR-08 工作流转换（平台侧状态回写）、CAP-27 工时登记、CAP-19 FR-09 附件内容代理（描述图片按需拉取）、
 * CAP-47 自建需求推送（候选/选项/可指派用户/推送/手动刷新）。
 */
@RestController
@RequestMapping("/api/projects/{pid}/requirements/{rid}/jira")
public class ProjectJiraIssueController {

    private final JiraIssueActionService service;
    private final JiraPushService pushService;

    public ProjectJiraIssueController(JiraIssueActionService service, JiraPushService pushService) {
        this.service = service;
        this.pushService = pushService;
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
     * CAP-47 FR-02：推送弹窗的一次性数据源（候选实例/默认目标/任务类型/优先级/可指派默认值/
     * 身份来源/是否被同步覆盖）。不抛错——选项拉取失败降级空表 + optionsError，弹窗一定打得开。
     */
    @GetMapping("/push-targets")
    public JiraPushTargetsView pushTargets(@PathVariable String pid, @PathVariable String rid) {
        return pushService.targets(pid, rid);
    }

    /** CAP-47 FR-02：切换实例/项目后重拉 Jira 项目 / 任务类型 / 优先级（失败即抛出，错误原文透出） */
    @GetMapping("/push-options")
    public JiraPushOptionsView pushOptions(@PathVariable String pid, @PathVariable String rid,
                                           @RequestParam("integrationId") Long integrationId,
                                           @RequestParam(value = "jiraProjectKey", required = false)
                                           String jiraProjectKey) {
        return pushService.options(pid, rid, integrationId, jiraProjectKey);
    }

    /** CAP-47 FR-02：经办人候选（q 为关键字，空取默认列表）；失败由前端降级为纯文本输入 */
    @GetMapping("/assignable-users")
    public List<JiraAssignableUserView> assignableUsers(@PathVariable String pid, @PathVariable String rid,
                                                        @RequestParam("integrationId") Long integrationId,
                                                        @RequestParam(value = "jiraProjectKey", required = false)
                                                        String jiraProjectKey,
                                                        @RequestParam(value = "q", required = false) String q) {
        return pushService.assignableUsers(pid, rid, integrationId, jiraProjectKey, q);
    }

    /**
     * CAP-47 FR-08：选定「实例 + 项目 + 任务类型」后的必填字段清单（createmeta）——
     * 弹窗据此动态渲染输入项。不抛错：拉取失败降级为空表 + error，提交不禁用。
     */
    @GetMapping("/create-fields")
    public JiraCreateFieldsView createFields(@PathVariable String pid, @PathVariable String rid,
                                             @RequestParam("integrationId") Long integrationId,
                                             @RequestParam("jiraProjectKey") String jiraProjectKey,
                                             @RequestParam("issueTypeId") String issueTypeId) {
        return pushService.createFields(pid, rid, integrationId, jiraProjectKey, issueTypeId);
    }

    /** CAP-47 FR-03：推送自建需求到 Jira（建 issue + 登记 link + 转 Jira 托管），幂等冲突报 409 */
    @PostMapping("/push")
    public JiraPushResultView push(@PathVariable String pid, @PathVariable String rid,
                                   @RequestBody JiraPushRequest req) {
        return pushService.push(pid, rid, req);
    }

    /** CAP-47 FR-05：按已关联 issue 手动刷新托管字段（JQL 不覆盖该 issue 时的兜底通道） */
    @PostMapping("/refresh")
    public JiraPushResultView refresh(@PathVariable String pid, @PathVariable String rid) {
        return pushService.refresh(pid, rid);
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
