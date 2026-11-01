package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.PlanSnapshot;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestPlanItemRepository;
import com.devmind.test.repo.TestPlanRepository;
import com.devmind.test.repo.TestPlanRunRepository;
import com.devmind.test.repo.TestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CAP-104 M3：分布式节点选择优先级、SHARD 稳定性与节点级预检分类。 */
class TestPlanNodeResolutionTest {

    private TestPlanRunService service;
    private EnvironmentService environmentService;
    private AgentNodeConnector connector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        environmentService = mock(EnvironmentService.class);
        connector = mock(AgentNodeConnector.class);
        ObjectProvider<AgentNodeConnector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connector);
        service = new TestPlanRunService(
                mock(TestPlanRunRepository.class), mock(TestPlanRepository.class),
                mock(TestPlanItemRepository.class), mock(TestRunRepository.class),
                mock(TestCaseRepository.class), mock(TestCaseResultRepository.class),
                mock(TestPlanService.class), mock(TestRunService.class),
                mock(ProjectService.class), environmentService,
                mock(EnvironmentPrecheckService.class), provider,
                mock(NotificationService.class), mock(DocumentService.class),
                mock(AuditService.class), mock(IdentityService.class),
                mock(DomainEventPublisher.class), mock(ExecutionLogHub.class),
                new ObjectMapper(), Duration.ofHours(2));
    }

    @Test
    void explicitNodesWinOverEnvironmentAndSelectors() {
        assertEquals(List.of("explicit-1"),
                service.resolveDistributedNodes(List.of("explicit-1"), 5L, "p1",
                        List.of("linux"), List.of("jdk21")));
    }

    @Test
    void environmentNodeGroupWinsOverSelectors() {
        EnvironmentEntity env = new EnvironmentEntity();
        env.setId(5L);
        when(environmentService.requireEnvironment("p1", 5L)).thenReturn(env);
        when(environmentService.nodeIdsOf(env)).thenReturn(List.of("env-a", "env-b"));

        assertEquals(List.of("env-a", "env-b"),
                service.resolveDistributedNodes(List.of(), 5L, "p1",
                        List.of("linux"), List.of("jdk21")));
    }

    @Test
    void labelsAndCapabilitiesMatchOnlineNodes() {
        when(connector.onlineNodesMatching(List.of("linux"), List.of("jdk21")))
                .thenReturn(List.of("n3", "n4"));
        assertEquals(List.of("n3", "n4"),
                service.resolveDistributedNodes(List.of(), null, "p1",
                        List.of("linux"), List.of("jdk21")));

        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.resolveDistributedNodes(List.of(), null, "p1",
                        List.of("gpu"), List.of()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void fallsBackToPlatformDefaultAndFailsWhenNoNodeExists() {
        when(connector.defaultNodeId()).thenReturn("default-node");
        assertEquals(List.of("default-node"),
                service.resolveDistributedNodes(List.of(), null, "p1", List.of(), List.of()));

        when(connector.defaultNodeId()).thenReturn(null);
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.resolveDistributedNodes(List.of(), null, "p1", List.of(), List.of()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void shardAssignmentIsStableDisjointAndComplete() {
        List<PlanSnapshot.TestCaseSnapshot> cases = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            cases.add(caseSnapshot(id, null));
        }
        List<String> nodes = List.of("n1", "n2", "n3");
        Set<Long> assigned = new HashSet<>();
        for (String node : nodes) {
            List<PlanSnapshot.TestCaseSnapshot> shard = TestPlanRunService.shardCases(cases, node, nodes);
            for (PlanSnapshot.TestCaseSnapshot c : shard) {
                assertTrue(assigned.add(c.id()), "用例被重复分片: " + c.id());
            }
            assertEquals(shard, TestPlanRunService.shardCases(cases, node, nodes));
        }
        assertEquals(cases.size(), assigned.size());
    }

    @Test
    void nodeScopedPrecheckItemsAreClassified() {
        assertTrue(TestPlanRunService.nodeScoped("n1/NODE_ONLINE"));
        assertTrue(TestPlanRunService.nodeScoped("n1/EXEC_PROTOCOL"));
        assertTrue(TestPlanRunService.nodeScoped("n1/COMMAND"));
        // 逐节点预检内部按裸名判定，持久化报告才加节点前缀
        assertTrue(TestPlanRunService.nodeScoped("NODE_ONLINE"));
        assertTrue(TestPlanRunService.nodeScoped("EXEC_PROTOCOL"));
        assertTrue(TestPlanRunService.nodeScoped("COMMAND"));
        assertFalse(TestPlanRunService.nodeScoped("n1/HTTP"));
        assertFalse(TestPlanRunService.nodeScoped("n1/VERSION_MATCH"));
        assertFalse(TestPlanRunService.nodeScoped(null));
    }

    private static PlanSnapshot.TestCaseSnapshot caseSnapshot(Long id, String caseKey) {
        return new PlanSnapshot.TestCaseSnapshot(id, 1L, 1, "case-" + id, "http", null, null,
                null, null, null, null, true, caseKey, "HTTP", null, null,
                "{}", 1, "APPROVED", null, null);
    }
}
