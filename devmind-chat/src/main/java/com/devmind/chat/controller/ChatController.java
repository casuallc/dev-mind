package com.devmind.chat.controller;

import com.devmind.chat.dto.ChatAuthorizeRequest;
import com.devmind.chat.dto.ChatInputRequest;
import com.devmind.chat.dto.ChatView;
import com.devmind.chat.dto.CreateChatRequest;
import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
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
 * CAP-30 通用问答 REST API。无 diff / worktree / template 端点（与项目会话完全分开）。
 */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatManagerService service;

    public ChatController(ChatManagerService service) {
        this.service = service;
    }

    @PostMapping
    public ChatView create(@Valid @RequestBody CreateChatRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<ChatView> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    public ChatView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/events")
    public List<SessionEvent> events(@PathVariable String id,
                                     @RequestParam(defaultValue = "-1") long afterSeq) {
        return service.events(id, afterSeq);
    }

    @PostMapping("/{id}/input")
    public void input(@PathVariable String id, @RequestBody ChatInputRequest req) {
        service.input(id, req.effectiveText(), req.effectiveImages());
    }

    @PostMapping("/{id}/authorize")
    public void authorize(@PathVariable String id, @RequestBody ChatAuthorizeRequest req) {
        service.authorize(id, req.acceptedOrFalse(), req.scope(), req.requestId());
    }

    @PostMapping("/{id}/suspend")
    public ChatView suspend(@PathVariable String id) {
        return service.suspend(id);
    }

    @PostMapping("/{id}/resume")
    public ChatView resume(@PathVariable String id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/kill")
    public ChatView kill(@PathVariable String id) {
        return service.kill(id);
    }

    @PostMapping("/{id}/finish")
    public void finish(@PathVariable String id) {
        service.finish(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.deleteChat(id);
    }
}
