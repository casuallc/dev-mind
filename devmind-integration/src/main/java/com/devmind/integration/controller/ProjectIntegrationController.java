package com.devmind.integration.controller;

import com.devmind.integration.dto.BindingRequest;
import com.devmind.integration.dto.BindingView;
import com.devmind.integration.dto.CreateMrRequest;
import com.devmind.integration.dto.ExternalLinkView;
import com.devmind.integration.dto.IntegrationCallView;
import com.devmind.integration.service.IntegrationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-18 项目作用域端点：绑定管理（FR-03）、WI 分支推送（FR-04）、创建 MR（FR-05）、
 * External Link 反查（FR-07）、调用日志（FR-08）。
 */
@RestController
@RequestMapping("/api/projects/{pid}")
public class ProjectIntegrationController {

    private final IntegrationService service;

    public ProjectIntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping("/integrations")
    public List<BindingView> bindings(@PathVariable String pid) {
        return service.listBindings(pid);
    }

    @PostMapping("/integrations")
    public BindingView bind(@PathVariable String pid, @RequestBody BindingRequest req) {
        return service.bind(pid, req);
    }

    @DeleteMapping("/integrations/{bindingId}")
    public Map<String, Object> unbind(@PathVariable String pid, @PathVariable Long bindingId) {
        service.unbind(pid, bindingId);
        return Map.of("ok", true);
    }

    /** FR-04 推送 WI 分支到绑定远程 */
    @PostMapping("/work-items/{wid}/push")
    public Map<String, Object> pushBranch(@PathVariable String pid, @PathVariable String wid) {
        String branch = service.pushWorkItemBranch(pid, wid);
        return Map.of("ok", true, "branch", branch);
    }

    /** FR-05 创建/复用 MR，登记 External Link */
    @PostMapping("/work-items/{wid}/merge-request")
    public ExternalLinkView createMr(@PathVariable String pid, @PathVariable String wid,
                                     @RequestBody(required = false) CreateMrRequest req) {
        return service.createMergeRequest(pid, wid, req);
    }

    /** FR-07 External Link 反查 */
    @GetMapping("/links")
    public List<ExternalLinkView> links(@PathVariable String pid,
                                        @RequestParam String internalType,
                                        @RequestParam String internalId) {
        return service.links(pid, internalType, internalId);
    }

    /** 项目内某类内部实体的全部外部链接（如 REQUIREMENT → Jira issue 徽标批量反查） */
    @GetMapping("/external-links")
    public List<ExternalLinkView> linksByType(@PathVariable String pid,
                                              @RequestParam String internalType) {
        return service.linksByType(pid, internalType);
    }

    /** FR-08 调用日志 */
    @GetMapping("/integration-calls")
    public List<IntegrationCallView> calls(@PathVariable String pid) {
        return service.calls(pid);
    }
}
