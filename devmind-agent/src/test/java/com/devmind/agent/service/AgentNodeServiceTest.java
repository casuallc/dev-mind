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
}
