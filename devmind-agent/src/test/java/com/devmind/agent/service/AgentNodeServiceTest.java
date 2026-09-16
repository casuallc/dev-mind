package com.devmind.agent.service;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AgentNodeService：平台默认节点互斥设定 / 禁用摘除标记 / defaultNodeId（mock repo，不拉起 Spring）。 */
class AgentNodeServiceTest {

    private final List<AgentNodeEntity> rows = new ArrayList<>();
    private AgentNodeService service;

    @BeforeEach
    void setUp() {
        rows.clear();
        AgentNodeRepository repo = mock(AgentNodeRepository.class);
        when(repo.findById(any())).thenAnswer(inv ->
                rows.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByIsDefaultTrue()).thenAnswer(inv ->
                rows.stream().filter(AgentNodeEntity::isDefault).toList());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AgentNodeService(repo);
    }

    private AgentNodeEntity addNode(long id, String status) {
        AgentNodeEntity e = new AgentNodeEntity();
        e.setId(id);
        e.setName("node-" + id);
        e.setTokenHash("hash-" + id);
        e.setStatus(status);
        rows.add(e);
        return e;
    }

    @Test
    void setDefaultClearsOtherNodes() {
        AgentNodeEntity a = addNode(1L, AgentNodeService.STATUS_ONLINE);
        AgentNodeEntity b = addNode(2L, AgentNodeService.STATUS_ONLINE);
        service.setDefault(1L, true);
        assertTrue(a.isDefault());
        service.setDefault(2L, true);
        assertFalse(a.isDefault(), "设定新默认节点应清除旧节点标记");
        assertTrue(b.isDefault());
        assertEquals("2", service.defaultNodeId());
    }

    @Test
    void unsetDefaultLeavesNoDefault() {
        addNode(1L, AgentNodeService.STATUS_ONLINE);
        service.setDefault(1L, true);
        service.setDefault(1L, false);
        assertNull(service.defaultNodeId());
    }

    @Test
    void disabledNodeCannotBeDefault() {
        addNode(1L, AgentNodeService.STATUS_DISABLED);
        assertThrows(DevMindException.class, () -> service.setDefault(1L, true));
    }

    @Test
    void disableStripsDefaultFlag() {
        AgentNodeEntity a = addNode(1L, AgentNodeService.STATUS_ONLINE);
        service.setDefault(1L, true);
        service.setDisabled(1L, true);
        assertFalse(a.isDefault(), "禁用节点应摘除平台默认标记");
        assertNull(service.defaultNodeId());
    }

    @Test
    void defaultNodeIdNullWhenEmpty() {
        assertNull(service.defaultNodeId());
    }

    // ---- CAP-43 节点外网代理 ----

    @Test
    void proxyUrlValidatedAndScopesNormalized() {
        AgentNodeEntity a = addNode(1L, AgentNodeService.STATUS_ONLINE);
        // 合法 URL + 空 scope → 默认 git
        service.update(1L, new com.devmind.agent.dto.UpdateAgentNodeRequest(
                null, "http://127.0.0.1:8443", null));
        assertEquals("http://127.0.0.1:8443", a.getProxyUrl());
        assertEquals("git", a.getProxyScopes());
        // 白名单子集保序去重
        service.update(1L, new com.devmind.agent.dto.UpdateAgentNodeRequest(
                null, null, "claude,git,claude"));
        assertEquals("claude,git", a.getProxyScopes());
        // 清空 URL → 连 scope 一起清
        service.update(1L, new com.devmind.agent.dto.UpdateAgentNodeRequest(null, "", null));
        assertNull(a.getProxyUrl());
        assertNull(a.getProxyScopes());
    }

    @Test
    void proxyUrlRejectsBadSchemeUserinfoAndEmptyHost() {
        addNode(1L, AgentNodeService.STATUS_ONLINE);
        assertThrows(DevMindException.class, () -> service.update(1L,
                new com.devmind.agent.dto.UpdateAgentNodeRequest(null, "socks5://127.0.0.1:1080", null)));
        DevMindException userinfo = assertThrows(DevMindException.class, () -> service.update(1L,
                new com.devmind.agent.dto.UpdateAgentNodeRequest(null, "http://u:p@127.0.0.1:8443", null)));
        assertTrue(userinfo.getMessage().contains("userinfo"), userinfo.getMessage());
        assertThrows(DevMindException.class, () -> service.update(1L,
                new com.devmind.agent.dto.UpdateAgentNodeRequest(null, "http://", null)));
        // 未知 scope
        assertThrows(DevMindException.class, () -> service.update(1L,
                new com.devmind.agent.dto.UpdateAgentNodeRequest(null, "http://127.0.0.1:8443", "git,ssh")));
    }

    @Test
    void proxyFieldsUntouchedWhenAbsent() {
        // labels-only 更新（proxyUrl=null）不动已有代理配置
        AgentNodeEntity a = addNode(1L, AgentNodeService.STATUS_ONLINE);
        service.update(1L, new com.devmind.agent.dto.UpdateAgentNodeRequest(
                null, "http://127.0.0.1:8443", "git"));
        service.update(1L, new com.devmind.agent.dto.UpdateAgentNodeRequest("windows", null, null));
        assertEquals("windows", a.getLabels());
        assertEquals("http://127.0.0.1:8443", a.getProxyUrl(), "labels 编辑不应清掉代理配置");
    }
}
