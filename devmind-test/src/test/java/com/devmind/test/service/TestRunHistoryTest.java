package com.devmind.test.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.devmind.auth.IdentityService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.docs.DocumentService;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.WorkItemService;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;
import com.devmind.test.repo.TestSuiteRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试历史列表的回归网：未关联工作单元的运行（workItemId=null，项目级回归/脚本套件直跑）
 * 不能 NPE——briefsOf 空表曾用 Map.of()，MapN.get(null) 直接抛 NullPointerException。
 */
class TestRunHistoryTest {

    private TestRunRepository repo;
    private TestRunService service;

    @BeforeEach
    void setUp() {
        repo = mock(TestRunRepository.class);
        TestCaseResultRepository resultRepo = mock(TestCaseResultRepository.class);
        when(resultRepo.findByRunIdOrderBySortAsc(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        service = new TestRunService(repo, resultRepo,
                mock(TestCaseRepository.class), mock(TestSuiteRepository.class),
                mock(ProjectService.class), mock(WorkItemService.class),
                mock(DeploymentRepository.class), mock(AgentNodeRouter.class),
                mock(AgentNodeStepRunner.class), mock(DocumentService.class),
                mock(NotificationService.class), mock(ExecutionLogHub.class),
                new ObjectMapper(), mock(EnvironmentService.class),
                mock(IdentityService.class), mock(DomainEventPublisher.class), Duration.ofHours(2));
    }

    private TestRunEntity run(Long id, String workItemId) {
        TestRunEntity r = new TestRunEntity();
        r.setId(id);
        r.setProjectId("p1");
        r.setWorkItemId(workItemId);
        r.setStatus(TestRunEntity.SUCCESS);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void historyWithUnlinkedRunsDoesNotThrow() {
        when(repo.findByProjectIdOrderByCreatedAtDesc("p1"))
                .thenReturn(List.of(run(1L, null), run(2L, null)));
        List<TestRunView> views = service.history("p1", null);
        assertEquals(2, views.size());
        assertNull(views.get(0).workItem());
        assertNull(views.get(1).workItem());
    }
}
