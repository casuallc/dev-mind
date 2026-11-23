package com.devmind.session.controller;

import com.devmind.session.dto.TerminalWorkspaceAttachRequest;
import com.devmind.session.dto.TerminalWorkspaceCreateRequest;
import com.devmind.session.dto.TerminalWorkspaceView;
import com.devmind.session.service.TerminalWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-115 终端工作区 REST（M1 登记/挂接/锁）。权限走 ApiPermissionCatalog：
 * project.terminal.action.manage（列表为登录可读）。
 */
@RestController
@RequestMapping("/api/terminal-workspaces")
public class TerminalWorkspaceController {

    private final TerminalWorkspaceService terminalWorkspaceService;

    public TerminalWorkspaceController(TerminalWorkspaceService terminalWorkspaceService) {
        this.terminalWorkspaceService = terminalWorkspaceService;
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
}
