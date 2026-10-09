package com.devmind.agent.service;

import com.devmind.agent.dto.EgressRuleRequest;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.agent.repo.EgressRuleRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-70 FR-03：规则 CRUD 校验（pattern 规范化/节点存在性）与路由判定（先命中先生效、
 * 停用规则不参与、未命中 = 直连）。
 */
class EgressRuleServiceTest {

    private final EgressRuleRepository repo = mock(EgressRuleRepository.class);
    private final AgentNodeRepository nodeRepo = mock(AgentNodeRepository.class);
    private final List<EgressRuleEntity> store = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final EgressRuleService service = new EgressRuleService(repo, nodeRepo,
            mock(ApplicationEventPublisher.class));

    private void stubStore() {
        when(repo.findAllByOrderBySortAscIdAsc()).thenAnswer(inv -> store.stream()
                .sorted(java.util.Comparator.comparingInt(EgressRuleEntity::getSort)
                        .thenComparing(EgressRuleEntity::getId))
                .toList());
        when(repo.save(any())).thenAnswer(inv -> {
            EgressRuleEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.incrementAndGet());
                store.add(e);
            }
            return e;
        });
        when(nodeRepo.existsById(1L)).thenReturn(true);
        AgentNodeEntity n = new AgentNodeEntity();
        n.setId(1L);
        n.setName("build-224");
        when(nodeRepo.findAll()).thenReturn(List.of(n));
    }

    @Test
    void createNormalizesPatternAndValidatesNode() {
        stubStore();
        var view = service.create(new EgressRuleRequest(" *.Corp.COM. ", 1L, null, null, "内网"), null);
        assertEquals("*.corp.com", view.hostPattern());
        assertTrue(view.enabled());
        assertEquals("build-224", view.nodeName());

        assertThrows(DevMindException.class,
                () -> service.create(new EgressRuleRequest("a.com", 99L, null, null, null), null));
        assertThrows(DevMindException.class,
                () -> service.create(new EgressRuleRequest("*.", 1L, null, null, null), null));
    }

    @Test
    void routeFirstMatchWinsAndDisabledSkipped() {
        stubStore();
        service.create(new EgressRuleRequest("*.corp.com", 1L, true, 10, null), null);
        service.create(new EgressRuleRequest("gitlab.corp.com", 1L, true, 5, null), null);
        service.create(new EgressRuleRequest("disabled.com", 1L, false, 1, null), null);

        assertEquals("gitlab.corp.com",
                service.findRoute("gitlab.corp.com").map(EgressRuleEntity::getHostPattern).orElseThrow());
        assertEquals("*.corp.com",
                service.findRoute("jira.corp.com").map(EgressRuleEntity::getHostPattern).orElseThrow());
        assertEquals(Optional.empty(), service.findRoute("github.com"));
        // 停用规则不参与匹配
        assertEquals(Optional.empty(), service.findRoute("disabled.com"));
        // 端口/大小写不敏感
        assertTrue(service.findRoute("GitLab.Corp.com:8443").isPresent());
    }

    @Test
    void allowedHostsSnapshotPerNode() {
        stubStore();
        service.create(new EgressRuleRequest("*.corp.com", 1L, true, 0, null), null);
        service.create(new EgressRuleRequest("off.com", 1L, false, 0, null), null);
        assertEquals(List.of("*.corp.com"), service.allowedHostsFor(1L));
        assertEquals(List.of(), service.allowedHostsFor(2L));
    }
}
