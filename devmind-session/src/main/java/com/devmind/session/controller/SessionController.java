package com.devmind.session.controller;

import com.devmind.session.dto.AuthorizeRequest;
import com.devmind.session.dto.CollectResultView;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.FinalizeRequest;
import com.devmind.session.dto.InputRequest;
import com.devmind.session.dto.OutputContentView;
import com.devmind.session.dto.OutputFileView;
import com.devmind.session.dto.RepoDiffView;
import com.devmind.session.dto.SessionView;
import com.devmind.common.agent.SessionEvent;
import com.devmind.session.service.SessionManagerService;
import jakarta.validation.Valid;
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
 * 会话 REST API。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManagerService service;

    public SessionController(SessionManagerService service) {
        this.service = service;
    }

    @PostMapping
    public SessionView create(@Valid @RequestBody CreateSessionRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<SessionView> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String projectId,
                                  @RequestParam(required = false) String workItemId,
                                  @RequestParam(required = false) String requirementId) {
        return service.list(status, projectId, workItemId, requirementId);
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * 事件补拉。CAP-50 起返回的是 seq &gt; afterSeq 的<b>最近</b>一段（升序），
     * {@code limit<=0} 取默认上限、超过硬上限按硬上限截断（见
     * {@link SessionManagerService#DEFAULT_EVENT_LIMIT}）。
     */
    @GetMapping("/{id}/events")
    public List<SessionEvent> events(@PathVariable String id,
                                     @RequestParam(defaultValue = "-1") long afterSeq,
                                     @RequestParam(defaultValue = "0") int limit) {
        return service.events(id, afterSeq, limit);
    }

    @PostMapping("/{id}/input")
    public void input(@PathVariable String id, @RequestBody InputRequest req) {
        service.input(id, req.effectiveText());
    }

    @PostMapping("/{id}/authorize")
    public void authorize(@PathVariable String id, @RequestBody AuthorizeRequest req) {
        service.authorize(id, req.accepted(), req.scope(), req.requestId());
    }

    @PostMapping("/{id}/suspend")
    public SessionView suspend(@PathVariable String id) {
        return service.suspend(id);
    }

    @PostMapping("/{id}/resume")
    public SessionView resume(@PathVariable String id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/kill")
    public SessionView kill(@PathVariable String id) {
        return service.kill(id);
    }

    @PostMapping("/{id}/finish")
    public void finish(@PathVariable String id) {
        service.finish(id);
    }

    /** CAP-42：固定工作区手动收口（合并会话分支到基线 + push + 删 worktree；鉴权=创建者或 admin）。 */
    @PostMapping("/{id}/finalize")
    public com.devmind.common.agent.FinalizeResult finalize(@PathVariable String id,
                                                            @RequestBody FinalizeRequest req) {
        return service.finalizeWorkspace(id, req != null && req.effectiveDiscardChanges());
    }

    /** CAP-31：按库返回 diff 摘要（本地逐库 worktree；远程经服务端克隆缓存 fetch 后 diff）。 */
    @GetMapping("/{id}/diff")
    public List<RepoDiffView> diff(@PathVariable String id) {
        return service.diff(id);
    }

    // ---------------- CAP-54 工作区实时视图（拉取侧；推送走 /ws/sessions/{id} 的 workspace 帧） ----------------

    /** 最新 git 快照：优先内存缓存；无缓存（未推过/终态）回 source 端点实时查询。 */
    @GetMapping("/{id}/workspace/status")
    public Map<String, Object> workspaceStatus(@PathVariable String id) {
        Map<String, Object> cached = service.latestWorkspaceSnapshot(id);
        return cached != null ? cached : service.workspaceQuery(id, "status", null, null);
    }

    @GetMapping("/{id}/workspace/tree")
    public Map<String, Object> workspaceTree(@PathVariable String id,
                                             @RequestParam(required = false) String path) {
        return service.workspaceQuery(id, "tree", null, path);
    }

    @GetMapping("/{id}/workspace/file")
    public Map<String, Object> workspaceFile(@PathVariable String id, @RequestParam String path) {
        return service.workspaceQuery(id, "file", null, path);
    }

    @GetMapping("/{id}/workspace/diff")
    public Map<String, Object> workspaceDiff(@PathVariable String id,
                                             @RequestParam(required = false) String repo,
                                             @RequestParam String path) {
        return service.workspaceQuery(id, "diff", repo, path);
    }

    @DeleteMapping("/{id}/worktree")
    public void removeWorktree(@PathVariable String id) {
        service.removeWorktree(id);
    }

    /** CAP-33 FR-07：已注入上下文清单（装配快照：场景/知识/skills/docs + 来源标注）。 */
    @GetMapping(value = "/{id}/context", produces = "application/json")
    public String context(@PathVariable String id) {
        return service.contextManifest(id); // 落库的即合法 JSON 快照，原样透传
    }

    /** CAP-39 FR-02：已回传产出文件列表（runner 上传的 .devmind/output/*）。 */
    @GetMapping("/{id}/outputs")
    public List<OutputFileView> outputs(@PathVariable String id) {
        return service.listOutputs(id);
    }

    /** CAP-39 FR-02：读产出内容（预览用）。 */
    @GetMapping("/{id}/outputs/{fileName}")
    public OutputContentView outputContent(@PathVariable String id, @PathVariable String fileName) {
        return service.getOutputContent(id, fileName);
    }

    /** CAP-39 FR-01/02：触发 runner 即时回传产出（collect_output 帧），降级提示见 message 字段。 */
    @PostMapping("/{id}/outputs/collect")
    public CollectResultView collectOutputs(@PathVariable String id) {
        return service.collectOutputs(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.deleteSession(id);
    }
}
