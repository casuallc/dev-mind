package com.devmind.session.controller;

import com.devmind.common.agent.AgentAuditBridge;
import com.devmind.common.agent.TerminalCompleteResult;
import com.devmind.common.agent.TerminalExecResult;
import com.devmind.session.dto.TerminalOpenView;
import com.devmind.session.dto.TerminalSessionView;
import com.devmind.session.dto.TerminalWorkspaceAttachRequest;
import com.devmind.session.dto.TerminalWorkspaceCreateRequest;
import com.devmind.session.dto.TerminalWorkspaceView;
import com.devmind.session.dto.TerminalWsCompleteRequest;
import com.devmind.session.dto.TerminalWsExecRequest;
import com.devmind.session.dto.TerminalWsFileWriteRequest;
import com.devmind.session.service.TerminalSessionService;
import com.devmind.session.service.TerminalWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-115 终端工作区 REST：M1 登记/挂接/锁 + M2 终端会话通道（open/exec/cancel/complete/
 * heartbeat/close）与文件树/读/写/命令历史。权限走 ApiPermissionCatalog：
 * project.terminal.action.manage（列表为登录可读）。
 */
@RestController
@RequestMapping("/api/terminal-workspaces")
public class TerminalWorkspaceController {

    private final TerminalWorkspaceService terminalWorkspaceService;
    private final TerminalSessionService terminalSessionService;

    public TerminalWorkspaceController(TerminalWorkspaceService terminalWorkspaceService,
                                       TerminalSessionService terminalSessionService) {
        this.terminalWorkspaceService = terminalWorkspaceService;
        this.terminalSessionService = terminalSessionService;
    }

    @GetMapping
    public List<TerminalWorkspaceView> list(@RequestParam String projectId) {
        return terminalWorkspaceService.list(projectId);
    }

    @GetMapping("/{id}")
    public TerminalWorkspaceView get(@PathVariable String id) {
        return terminalWorkspaceService.get(id);
    }

    /** 新建：term-<短id> 键 + terminal/ 分支，runner 幂等物化成功后落库。 */
    @PostMapping
    public TerminalWorkspaceView create(@RequestBody TerminalWorkspaceCreateRequest req) {
        return terminalWorkspaceService.create(req);
    }

    /** 挂接既有工作区键（req-/sid-/term- 前缀）。 */
    @PostMapping("/attach")
    public TerminalWorkspaceView attach(@RequestBody TerminalWorkspaceAttachRequest req) {
        return terminalWorkspaceService.attach(req);
    }

    /** 加锁/续期：他人新鲜锁 409 带占用方，过期锁抢占。 */
    @PostMapping("/{id}/lock")
    public TerminalWorkspaceView lock(@PathVariable String id) {
        return terminalWorkspaceService.lock(id);
    }

    /** 解锁：本人或管理员；未持锁幂等。 */
    @PostMapping("/{id}/unlock")
    public TerminalWorkspaceView unlock(@PathVariable String id) {
        return terminalWorkspaceService.unlock(id);
    }

    // ---------------- M2 终端会话通道 ----------------

    /** 打开终端：占用锁 → 重发 terminal_ws_prepare 幂等重绑 → 会话 RUNNING（SUSPENDED 复活）。 */
    @PostMapping("/{id}/terminal/open")
    public TerminalOpenView openTerminal(@PathVariable String id) {
        return terminalSessionService.open(id);
    }

    /** 执行命令：危险命令未 confirmed → 428（前端二次确认重发）；confirmed 放行并审计确认人。 */
    @PostMapping("/{id}/terminal/exec")
    public TerminalExecResult exec(@PathVariable String id, @RequestBody TerminalWsExecRequest req) {
        return terminalSessionService.exec(id, req.command(), req.cwd(), req.confirmed());
    }

    /** 取消执行中命令（fire-and-forget）。 */
    @PostMapping("/{id}/terminal/cancel")
    public Map<String, Boolean> cancel(@PathVariable String id) {
        terminalSessionService.cancel(id);
        return Map.of("ok", true);
    }

    /** Tab 补全。 */
    @PostMapping("/{id}/terminal/complete")
    public TerminalCompleteResult complete(@PathVariable String id,
                                           @RequestBody TerminalWsCompleteRequest req) {
        return terminalSessionService.complete(id, req.input(), req.cwd());
    }

    /** 心跳：续期会话 last_heartbeat_at + 工作区锁（前端 30s 一跳；90s 无心跳巡检挂起）。 */
    @PostMapping("/{id}/terminal/heartbeat")
    public TerminalSessionView heartbeat(@PathVariable String id) {
        return terminalSessionService.heartbeat(id);
    }

    /** 关闭终端：会话 CLOSED + 释放本人占用锁（幂等）。 */
    @PostMapping("/{id}/terminal/close")
    public TerminalSessionView close(@PathVariable String id) {
        return terminalSessionService.close(id);
    }

    // ---------------- M2 文件读写 / 命令历史 ----------------

    /** 文件树（复用 CAP-54 workspace_query tree 只读帧）。 */
    @GetMapping("/{id}/files/tree")
    public Map<String, Object> fileTree(@PathVariable String id,
                                        @RequestParam(required = false) String path) {
        return terminalSessionService.fileTree(id, path == null ? "" : path);
    }

    /** 文件内容（复用 CAP-54 workspace_query file 只读帧）。 */
    @GetMapping("/{id}/files/file")
    public Map<String, Object> fileRead(@PathVariable String id, @RequestParam String path) {
        return terminalSessionService.fileRead(id, path);
    }

    /** 文件直写（workspace_file_write 帧，expectedSha 乐观锁：不符 → 409 带当前 sha）。 */
    @PutMapping("/{id}/files/file")
    public Map<String, Object> fileWrite(@PathVariable String id,
                                         @RequestBody TerminalWsFileWriteRequest req) {
        return terminalSessionService.fileWrite(id, req.path(), req.contentBase64(), req.expectedSha());
    }

    /** 命令历史：COMMAND 类审计按 workspaceKey 倒序（最近 limit 条，上限 200）。 */
    @GetMapping("/{id}/history")
    public List<AgentAuditBridge.Entry> history(@PathVariable String id,
                                                @RequestParam(defaultValue = "50") int limit) {
        return terminalSessionService.history(id, limit);
    }

    // ---------------- M3 收口 / 释放 / 收口记录 ----------------

    /**
     * M3 收口：confirmCode 服务端硬校验（=workspaceKey）→ runner ff-merge 基线 + push → FINALIZED。
     * 脏工作区且未 discardChanges → 409 列前 3 个改动文件；锁/运行中终端会话占用 → 409。
     */
    @PostMapping("/{id}/finalize")
    public com.devmind.session.dto.FinalizeDetailView finalize(
            @PathVariable String id,
            @RequestBody com.devmind.session.dto.TerminalFinalizeRequest req) {
        return terminalWorkspaceService.finalize(id, req);
    }

    /** M3 收口记录查看（未收口过 detail=null）。 */
    @GetMapping("/{id}/finalize-detail")
    public com.devmind.session.dto.FinalizeDetailView finalizeDetail(@PathVariable String id) {
        return terminalWorkspaceService.finalizeDetail(id);
    }

    /** M3 释放（丢弃语义）：脏工作区默认 409，discardChanges=true 强制；成功落 RELEASED。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public TerminalWorkspaceView release(@PathVariable String id,
                                         @RequestParam(defaultValue = "false") boolean discardChanges) {
        return terminalWorkspaceService.release(id, discardChanges);
    }
}
