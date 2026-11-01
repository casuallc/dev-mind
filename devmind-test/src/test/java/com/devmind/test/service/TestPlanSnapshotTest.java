package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.PlanSnapshot;
import com.devmind.test.dto.TestPlanRequest;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseVersionEntity;
import com.devmind.test.model.TestPlanEntity;
import com.devmind.test.model.TestPlanItemEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseVersionRepository;
import com.devmind.test.repo.TestPlanItemRepository;
import com.devmind.test.repo.TestPlanRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-104 M2：方案快照固化运行级覆盖，并区分保存期/运行期的评审门禁。 */
class TestPlanSnapshotTest {

    private TestPlanService service;
    private TestCaseRepository caseRepo;
    private TestPlanRepository planRepo;
    private TestPlanItemRepository itemRepo;

    @BeforeEach
    void setUp() {
        planRepo = mock(TestPlanRepository.class);
        caseRepo = mock(TestCaseRepository.class);
        itemRepo = mock(TestPlanItemRepository.class);
        when(planRepo.save(any(TestPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new TestPlanService(planRepo, itemRepo, caseRepo,
                mock(TestCaseVersionRepository.class), mock(TestSuiteRepository.class),
                mock(ProjectService.class), mock(EnvironmentService.class),
                mock(IdentityService.class), mock(AuditService.class), new ObjectMapper());
    }

    @Test
    void snapshotUsesRunOverridesAndCleansNodeSelectors() {
        TestCaseEntity c = approvedCase(TestCaseVersionEntity.APPROVED);
        when(caseRepo.findById(11L)).thenReturn(Optional.of(c));
        TestPlanEntity plan = plan();
        TestPlanItemEntity item = item();

        PlanSnapshot snap = service.buildSnapshot(plan, List.of(item), "SHARD",
                List.of("node-1", "node-2"), "node-1", 5L, "http://target", "req-1",
                Map.of("k", "v"), "FAIL_FAST", 4, 90,
                List.of(" linux ", "linux"), List.of("jdk21"));

        assertEquals("FAIL_FAST", snap.failStrategy());
        assertEquals(4, snap.concurrency());
        assertEquals(90, snap.timeoutSec());
        assertEquals(List.of("linux"), snap.labels());
        assertEquals(List.of("jdk21"), snap.capabilities());
        assertEquals(List.of("node-1", "node-2"), snap.nodeIds());
        assertEquals("SHARD", snap.mode());
        assertEquals(1, snap.items().size());
        assertEquals("case-1", snap.items().get(0).cases().get(0).caseKey());
    }

    @Test
    void snapshotFallsBackToPlanDefaultsAndRejectsInvalidOverrides() {
        TestCaseEntity c = approvedCase(TestCaseVersionEntity.APPROVED);
        when(caseRepo.findById(11L)).thenReturn(Optional.of(c));
        PlanSnapshot snap = service.buildSnapshot(plan(), List.of(item()), "SEQUENTIAL",
                List.of(), null, null, null, null, Map.of(), null, null, null, null, null);
        assertEquals("CONTINUE", snap.failStrategy());
        assertEquals(2, snap.concurrency());

        DevMindException badParallel = assertThrows(DevMindException.class,
                () -> service.buildSnapshot(plan(), List.of(item()), "SEQUENTIAL",
                        List.of(), null, null, null, null, Map.of(), null, 0, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, badParallel.getErrorCode());

        DevMindException badTimeout = assertThrows(DevMindException.class,
                () -> service.buildSnapshot(plan(), List.of(item()), "SEQUENTIAL",
                        List.of(), null, null, null, null, Map.of(), null, null, 0, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, badTimeout.getErrorCode());
    }

    @Test
    void runSnapshotAllowsDeprecatedCaseButSaveStillRejectsIt() {
        TestCaseEntity c = approvedCase(TestCaseVersionEntity.DEPRECATED);
        when(caseRepo.findById(11L)).thenReturn(Optional.of(c));

        PlanSnapshot snap = service.buildSnapshot(plan(), List.of(item()), "SEQUENTIAL",
                List.of(), null, null, null, null, Map.of(), null, null, null, null, null);
        assertEquals(TestCaseVersionEntity.DEPRECATED,
                snap.items().get(0).cases().get(0).reviewStatus());

        TestPlanRequest request = new TestPlanRequest("plan", null, null, "CONTINUE", 2, Map.of(),
                null, false, List.of(new TestPlanRequest.TestPlanItemInput(
                "CASE", 11L, null, 1, null, Map.of(), true)));
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.create("p1", request));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void viewMasksSensitivePlanParametersAndOverrideValues() {
        TestPlanEntity plan = plan();
        plan.setParametersJson("{\"apiToken\":\"real-token\",\"env\":\"dev\"}");
        TestPlanItemEntity item = item();
        item.setOverridesJson("{\"env\":{\"accessToken\":\"raw-token\",\"region\":\"cn\"},"
                + "\"parameters\":{\"password\":\"pwd\",\"k\":\"v\"}}");
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(item));
        when(caseRepo.findById(11L)).thenReturn(Optional.of(approvedCase(TestCaseVersionEntity.APPROVED)));

        var view = service.toView(plan);

        assertEquals("******", view.parameters().get("apiToken"));
        assertEquals("dev", view.parameters().get("env"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) view.items().get(0).overrides().get("env");
        assertEquals("******", env.get("accessToken"));
        assertEquals("cn", env.get("region"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) view.items().get(0).overrides().get("parameters");
        assertEquals("******", params.get("password"));
        assertEquals("v", params.get("k"));
    }

    @Test
    void updateRestoresMaskedSecretsFromStoredValues() {
        TestPlanEntity existing = plan();
        existing.setParametersJson("{\"apiToken\":\"real-token\",\"env\":\"dev\"}");
        TestPlanItemEntity previous = item();
        previous.setOverridesJson("{\"env\":{\"accessToken\":\"raw-token\",\"region\":\"cn\"}}");
        when(planRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(previous));
        when(caseRepo.findById(11L)).thenReturn(Optional.of(approvedCase(TestCaseVersionEntity.APPROVED)));

        TestPlanRequest request = new TestPlanRequest("plan2", null, null, "CONTINUE", 2,
                Map.of("apiToken", "******", "env", "prod"), null, false,
                List.of(new TestPlanRequest.TestPlanItemInput("CASE", 11L, null, 1, null,
                        Map.of("env", Map.of("accessToken", "******", "region", "us")), true)));
        service.update(1L, request);

        assertTrue(existing.getParametersJson().contains("real-token"));
        assertTrue(existing.getParametersJson().contains("prod"));
        assertFalse(existing.getParametersJson().contains("******"));
        ArgumentCaptor<TestPlanItemEntity> captor = ArgumentCaptor.forClass(TestPlanItemEntity.class);
        verify(itemRepo).save(captor.capture());
        String overrides = captor.getValue().getOverridesJson();
        assertTrue(overrides.contains("raw-token"));
        assertTrue(overrides.contains("\"region\":\"us\""));
        assertFalse(overrides.contains("******"));
    }

    @Test
    void createRejectsMaskedPlaceholderWithoutStoredSecret() {
        when(caseRepo.findById(11L)).thenReturn(Optional.of(approvedCase(TestCaseVersionEntity.APPROVED)));
        TestPlanRequest request = new TestPlanRequest("plan", null, null, "CONTINUE", 2,
                Map.of("apiToken", "******"), null, false,
                List.of(new TestPlanRequest.TestPlanItemInput("CASE", 11L, null, 1, null, Map.of(), true)));

        DevMindException ex = assertThrows(DevMindException.class, () -> service.create("p1", request));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    private static TestPlanEntity plan() {
        TestPlanEntity plan = new TestPlanEntity();
        plan.setId(1L);
        plan.setProjectId("p1");
        plan.setName("plan");
        plan.setPlanVersion(3);
        plan.setFailStrategy(TestPlanEntity.CONTINUE);
        plan.setConcurrency(2);
        return plan;
    }

    private static TestPlanItemEntity item() {
        TestPlanItemEntity item = new TestPlanItemEntity();
        item.setId(101L);
        item.setRefType(TestPlanItemEntity.REF_CASE);
        item.setRefId(11L);
        item.setSeq(1);
        item.setEnabled(true);
        return item;
    }

    private static TestCaseEntity approvedCase(String reviewStatus) {
        TestCaseEntity c = new TestCaseEntity();
        c.setId(11L);
        c.setSuiteId(21L);
        c.setSort(1);
        c.setName("case");
        c.setCaseKey("case-1");
        c.setEnabled(true);
        c.setReviewStatus(reviewStatus);
        c.setCaseVersion(1);
        c.setSpecJson("{}");
        return c;
    }
}
