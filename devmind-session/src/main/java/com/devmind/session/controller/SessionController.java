package com.devmind.session.controller;

import com.devmind.project.WorktreeManager;
import com.devmind.session.dto.AuthorizeRequest;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.DiffView;
import com.devmind.session.dto.InputRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEvent;
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
                                  @RequestParam(required = false) String requirementId) {
        return service.list(status, projectId, requirementId);
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/events")
    public List<SessionEvent> events(@PathVariable String id,
                                     @RequestParam(defaultValue = "-1") long afterSeq) {
        return service.events(id, afterSeq);
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

    @GetMapping("/{id}/diff")
    public DiffView diff(@PathVariable String id) {
        WorktreeManager.DiffResult d = service.diff(id);
        return new DiffView(d.stat(), d.files(), d.hasChanges());
    }

    @DeleteMapping("/{id}/worktree")
    public void removeWorktree(@PathVariable String id) {
        service.removeWorktree(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.deleteSession(id);
    }
}
