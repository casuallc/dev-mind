package com.devmind.agent.controller;

import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.dto.CreateAgentNodeRequest;
import com.devmind.agent.dto.IssuedNodeView;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentNodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-21 节点管理端点（/api/agent-nodes）。创建返回的 token 仅此一次可见。
 * 变更操作在 SecurityConfig 限定 ADMIN。
 */
@RestController
@RequestMapping("/api/agent-nodes")
public class AgentNodeController {

    private final AgentNodeService service;
    private final AgentConnectionRegistry registry;

    public AgentNodeController(AgentNodeService service, AgentConnectionRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @PostMapping
    public IssuedNodeView create(@Valid @RequestBody CreateAgentNodeRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<AgentNodeView> list() {
        return service.list();
    }

    @PostMapping("/{id}/disable")
    public AgentNodeView disable(@PathVariable Long id) {
        AgentNodeView view = service.setDisabled(id, true);
        registry.evict(id);
        return view;
    }

    @PostMapping("/{id}/enable")
    public AgentNodeView enable(@PathVariable Long id) {
        return service.setDisabled(id, false);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        registry.evict(id);
        service.delete(id);
    }
}
