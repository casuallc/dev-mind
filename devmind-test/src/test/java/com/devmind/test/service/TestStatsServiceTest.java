package com.devmind.test.service;

import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.TestStatsView;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestPlanRunRepository;
import com.devmind.test.repo.TestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CAP-104 FR-21：通过率口径与 flaky（混合结果 / 重试后通过）。 */
class TestStatsServiceTest {

    private TestRunRepository runRepo;
    private TestCaseResultRepository resultRepo;
    private EnvironmentService environmentService;
    private TestStatsService service;

    @BeforeEach
    void setUp() {
        runRepo = mock(TestRunRepository.class);
        resultRepo = mock(TestCaseResultRepository.class);
        environmentService = mock(EnvironmentService.class);
        when(environmentService.list("p1")).thenReturn(List.of());
        service = new TestStatsService(mock(ProjectService.class), environmentService,
                runRepo, mock(TestPlanRunRepository.class), resultRepo,
                mock(TestCaseRepository.class));
    }

    @Test
    void passRateExcludesBlockedAndFlakyUsesMixedResults() {
        Instant now = Instant.now();
        TestRunEntity r1 = run(1L, now.minusSeconds(7200), null, null);
        TestRunEntity r2 = run(2L, now.minusSeconds(3600), null, null);
        when(runRepo.findByProjectIdAndCreatedAtAfterOrderByCreatedAtAsc(eq("p1"), any(Instant.class)))
                .thenReturn(List.of(r1, r2));
        when(resultRepo.findByRunIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                result(1L, 10L, "pass", 100L, now.minusSeconds(3000), null),
                result(2L, 10L, "fail", 300L, now.minusSeconds(1800), "assertion failed"),
                result(2L, 12L, "blocked", null, now.minusSeconds(1700), "env down")));

        TestStatsView view = service.stats("p1", 30, "DAY", 2, null, null, 10);

        assertEquals(2, view.overview().get("runs"));
        assertEquals(3, view.overview().get("cases"));
        assertEquals(50.0, view.overview().get("passRate"));
        assertEquals(1, view.flaky().size());
        assertEquals(10L, view.flaky().get(0).caseId());
        assertEquals(50.0, view.flaky().get(0).failRate());
        assertEquals(1, view.topFailures().size());
        assertEquals(300L, view.duration().get("p95Ms"));
        assertNotNull(view.trend());
    }

    @Test
    void retryPassMarksCaseFlakyEvenBelowSampleThreshold() {
        Instant now = Instant.now();
        TestRunEntity first = run(3L, now.minusSeconds(2400), 7L, 1);
        TestRunEntity second = run(4L, now.minusSeconds(1800), 7L, 2);
        when(runRepo.findByProjectIdAndCreatedAtAfterOrderByCreatedAtAsc(eq("p1"), any(Instant.class)))
                .thenReturn(List.of(first, second));
        when(resultRepo.findByRunIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                result(3L, 11L, "fail", 200L, now.minusSeconds(2390), "flaky"),
                result(4L, 11L, "pass", 180L, now.minusSeconds(1790), null)));

        TestStatsView view = service.stats("p1", 30, "DAY", 5, null, null, 10);

        assertEquals(1, view.flaky().size());
        assertTrue(view.flaky().get(0).retryPass());
        assertEquals(11L, view.flaky().get(0).caseId());
    }

    private static TestRunEntity run(Long id, Instant createdAt, Long planItemId, Integer attempt) {
        TestRunEntity r = new TestRunEntity();
        r.setId(id);
        r.setProjectId("p1");
        r.setCreatedAt(createdAt);
        r.setStartedAt(createdAt);
        r.setFinishedAt(createdAt.plusSeconds(60));
        r.setStatus(TestRunEntity.SUCCESS);
        r.setPlanItemId(planItemId);
        r.setAttempt(attempt);
        return r;
    }

    private static TestCaseResultEntity result(Long runId, Long caseId, String status, Long duration,
                                               Instant createdAt, String error) {
        TestCaseResultEntity r = new TestCaseResultEntity();
        r.setRunId(runId);
        r.setCaseId(caseId);
        r.setName("case-" + caseId);
        r.setStatus(status);
        r.setDuration(duration);
        r.setCreatedAt(createdAt);
        r.setError(error);
        return r;
    }
}
