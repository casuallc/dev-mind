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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话 REST API。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManagerService service;

    /** 列表 taskSpec/summary 预览长度（列表 UI 只展示前 40 字；留 200 余量给 tooltip） */
    private static final int LIST_PREVIEW_CHARS = 200;

    public SessionController(SessionManagerService service) {
        this.service = service;
    }

    @PostMapping
    public SessionView create(@Valid @RequestBody CreateSessionRequest req) {
        return service.create(req);
    }

    /**
     * 会话列表。taskSpec/summary 只回预览（全文走 /{id}——看板轮询不需要全文）；
     * keyword 服务端 LIKE 搜索（字段截断后前端本地搜会漏）。
     * 带 ETag 条件请求：看板 3s 轮询无变化时回 304 空体，带宽≈0
     * （Cache-Control: no-cache 强制每次回源校验；显式设置也避免安全头默认 no-store 吃掉浏览器缓存）。
     */
    @GetMapping
    public ResponseEntity<List<SessionView>> list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String projectId,
                                                  @RequestParam(required = false) String workItemId,
                                                  @RequestParam(required = false) String requirementId,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestHeader(value = "If-None-Match", required = false)
                                                  String ifNoneMatch) {
        List<SessionView> views = service.list(status, projectId, workItemId, requirementId, keyword)
                .stream().map(v -> v.withPreview(LIST_PREVIEW_CHARS)).toList();
        String etag = "\"" + Integer.toHexString(fingerprint(views)) + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).eTag(etag).body(views);
    }

    /** 轮询变更指纹：只看 UI 展示的字段，任一变化即换 ETag（列表内容本身已是最新快照，无需另查库） */
    private static int fingerprint(List<SessionView> views) {
        int h = 1;
        for (SessionView v : views) {
            h = 31 * h + Objects.hash(v.id(), v.status(), v.workspaceState(), v.updatedAt(),
                    v.taskSpec(), v.summary(), v.turnCount(), v.costUsd(), v.repoNames());
        }
        return h;
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * 事件补拉。CAP-50 起返回的是 seq &gt; afterSeq 的<b>最近</b>一段（升序），
     * {@code limit<=0} 取默认上限、超过硬上限按硬上限截断（见
     * {@link SessionManagerService#DEFAULT_EVENT_LIMIT}）；
     * {@code beforeSeq} 为向前翻页下界（只取 seq 小于它的一页），默认不限。
     * {@code payloadMax>0} 时 tool_use/tool_result 大字段截断为预览（payload 带 previewTruncated 标记），
     * 全文走 {@code GET /{id}/events/{seq}}。
     */
    @GetMapping("/{id}/events")
    public List<SessionEvent> events(@PathVariable String id,
                                     @RequestParam(defaultValue = "-1") long afterSeq,
                                     @RequestParam(defaultValue = "0") int limit,
                                     @RequestParam(defaultValue = "9223372036854775807") long beforeSeq,
                                     @RequestParam(defaultValue = "0") int payloadMax) {
        return service.events(id, afterSeq, limit, beforeSeq, payloadMax);
    }

    /** 单条事件全文：预览截断（payloadMax）后的补取通道，前端展开工具卡片时按 seq 拉取 */
    @GetMapping("/{id}/events/{seq}")
    public SessionEvent event(@PathVariable String id, @PathVariable long seq) {
        return service.event(id, seq);
    }

    @PostMapping("/{id}/input")
    public void input(@PathVariable String id, @RequestBody InputRequest req) {
        // CAP-68：REST 与 WS 同语义——附件引用（images/files）一并解析下发
        service.input(id, req.effectiveText(), req.images(), req.files());
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

    /** CAP-42：固定工作区手动收口（合并会话分支到基线 + push + 删 worktree；鉴权=创建者或 admin）。
     *  deleteRemoteBranch=true 时收口成功追加删除远端会话分支（协议 v19，diff 随之不可再查）。 */
    @PostMapping("/{id}/finalize")
    public com.devmind.common.agent.FinalizeResult finalize(@PathVariable String id,
                                                            @RequestBody FinalizeRequest req) {
        return service.finalizeWorkspace(id, req != null && req.effectiveDiscardChanges(),
                req != null && req.effectiveDeleteRemoteBranch());
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

    /**
     * CAP-58：终端单条命令执行（body: command 必填、cwd 可选=相对代码目录）。
     * 命令非零退出仍 200（exitCode 带回）；白名单拒绝/越界/老 runner 走 CONFLICT。
     */
    @PostMapping("/{id}/terminal/exec")
    public com.devmind.common.agent.TerminalExecResult terminalExec(@PathVariable String id,
                                                                    @RequestBody TerminalExecRequest req) {
        return service.terminalExec(id, req == null ? null : req.command(),
                req == null ? null : req.cwd());
    }

    /** CAP-58 终端请求体。 */
    public record TerminalExecRequest(String command, String cwd) {
    }

    /**
     * CAP-59：Tab 补全（body: input 当前输入行、cwd 可选=相对代码目录）。
     * 老 runner（v17-）走 CONFLICT 引导升级，前端回落本地历史补全。
     */
    @PostMapping("/{id}/terminal/complete")
    public com.devmind.common.agent.TerminalCompleteResult terminalComplete(@PathVariable String id,
                                                                            @RequestBody TerminalCompleteRequest req) {
        return service.terminalComplete(id, req == null ? null : req.input(),
                req == null ? null : req.cwd());
    }

    /** CAP-59 补全请求体。 */
    public record TerminalCompleteRequest(String input, String cwd) {
    }

    /** CAP-59：取消执行中的终端命令（无进行中命令 = no-op）。 */
    @PostMapping("/{id}/terminal/cancel")
    public void terminalCancel(@PathVariable String id) {
        service.terminalCancel(id);
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
