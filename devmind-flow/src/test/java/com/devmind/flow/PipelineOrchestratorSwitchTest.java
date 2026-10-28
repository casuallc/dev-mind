package com.devmind.flow;

import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.build.service.BuildService;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.deploy.dto.CreateDeploymentRequest;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.model.DeployConfigEntity;
import com.devmind.deploy.repo.DeployConfigRepository;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.project.repo.EnvironmentRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-72 执行链项目级开关单测（无 Spring 上下文，依赖全部子类 fake / 接口代理）。
 * 覆盖 FR-02：两个自动环节各自的闸门（关=静默跳过，开=走原链路），
 * 补扫路径（retryPendingBuilds）同受闸门；项目行不存在按关处理（安全侧）。
 */
class PipelineOrchestratorSwitchTest {

    private static final String PID = "p1";
    private static final String WI_ID = "wi-1";

    private final Map<String, ProjectEntity> projects = new LinkedHashMap<>();
    private final Map<String, WorkItemEntity> workItems = new LinkedHashMap<>();
    private final List<Object> buildSteps = new ArrayList<>();
    private final Map<Long, BuildEntity> builds = new LinkedHashMap<>();
    private final List<EnvironmentEntity> envs = new ArrayList<>();
    private DeployConfigEntity deployConfig;

    private FakeBuildService buildService;
    private FakeDeploymentService deploymentService;
    private PipelineOrchestrator orchestrator;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** 接口代理兜底返回值（List/Optional/数值/布尔），未覆盖的方法不炸。 */
    private static Object defaultValue(Class<?> type) {
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Optional.class.isAssignableFrom(type)) return Optional.empty();
        if (type == long.class || type == Long.class) return 0L;
        if (type == int.class || type == Integer.class) return 0;
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }

    static class FakeBuildService extends BuildService {
        final List<TriggerRequest> triggers = new ArrayList<>();

        FakeBuildService() {
            super(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, Duration.ofHours(2));
        }

        @Override
        public BuildView trigger(String projectId, TriggerRequest req) {
            triggers.add(req);
            return new BuildView(999L, projectId, req.workItemId(), null, null, "LOCAL", null,
                    BuildEntity.QUEUED, null, null, null, null, Instant.now(), null);
        }
    }

    static class FakeDeploymentService extends DeploymentService {
        final List<CreateDeploymentRequest> creates = new ArrayList<>();
        final List<Long> executes = new ArrayList<>();

        FakeDeploymentService() {
            super(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, Duration.ofHours(2));
        }

        @Override
        public DeploymentView create(CreateDeploymentRequest req) {
            creates.add(req);
            return new DeploymentView(7L, req.projectId(), req.workItemId(), null, req.environmentId(),
                    req.buildId(), null, "QUEUED", null, null, null, false, false, null, "test",
                    null, null, Instant.now(), List.of(), List.of(), null);
        }

        @Override
        public DeploymentView execute(Long id) {
            executes.add(id);
            return null;
        }
    }

    static class FakeWorkItemService extends WorkItemService {
        private final Map<String, WorkItemEntity> store;

        FakeWorkItemService(Map<String, WorkItemEntity> store) {
            super(null, null, null, null, null, null, null);
            this.store = store;
        }

        @Override
        public WorkItemEntity requireById(String workItemId) {
            return store.get(workItemId);
        }
    }

    @BeforeEach
    void setUp() {
        projects.clear();
        workItems.clear();
        buildSteps.clear();
        builds.clear();
        envs.clear();
        deployConfig = null;

        buildSteps.add(new com.devmind.project.model.BuildStepEntity());
        workItems.put(WI_ID, workItem(WI_ID, WorkItemEntity.STATUS_DONE));

        ProjectRepository projectRepo = proxy(ProjectRepository.class, (p, m, args) -> {
            if (m.getName().equals("findById")) return Optional.ofNullable(projects.get(args[0]));
            return defaultValue(m.getReturnType());
        });
        BuildStepRepository buildStepRepo = proxy(BuildStepRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByProjectIdOrderBySortOrderAsc")) return buildSteps;
            return defaultValue(m.getReturnType());
        });
        BuildRepository buildRepo = proxy(BuildRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByWorkItemIdOrderByCreatedAtDesc")) {
                return builds.values().stream().filter(b -> args[0].equals(b.getWorkItemId())).toList();
            }
            if (m.getName().equals("findByProjectIdOrderByCreatedAtDesc")) {
                return builds.values().stream().filter(b -> args[0].equals(b.getProjectId())).toList();
            }
            if (m.getName().equals("findById")) return Optional.ofNullable(builds.get(args[0]));
            return defaultValue(m.getReturnType());
        });
        EnvironmentRepository environmentRepo = proxy(EnvironmentRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByProjectIdOrderByIdAsc")) return envs;
            return defaultValue(m.getReturnType());
        });
        DeployConfigRepository deployConfigRepo = proxy(DeployConfigRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByProjectId")) return deployConfig;
            return defaultValue(m.getReturnType());
        });
        WorkItemRepository workItemRepo = proxy(WorkItemRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByProjectIdOrderBySeqDesc")) return List.copyOf(workItems.values());
            if (m.getName().equals("findByRequirementIdOrderBySeqAsc")) {
                return workItems.values().stream()
                        .filter(w -> args[0].equals(w.getRequirementId())).toList();
            }
            return defaultValue(m.getReturnType());
        });
        DeploymentRepository deploymentRepo = proxy(DeploymentRepository.class,
                (p, m, args) -> defaultValue(m.getReturnType()));

        buildService = new FakeBuildService();
        deploymentService = new FakeDeploymentService();
        orchestrator = new PipelineOrchestrator(
                new FakeWorkItemService(workItems), workItemRepo, buildStepRepo, buildRepo, buildService,
                deploymentService, deployConfigRepo, deploymentRepo, environmentRepo,
                new FakeNotificationService(), projectRepo);
    }

    static class FakeNotificationService extends NotificationService {
        FakeNotificationService() {
            super(null, null, null, null, null, List.of(), null);
        }

        @Override
        public com.devmind.notification.dto.NotificationView emit(
                com.devmind.notification.dto.NotificationDraft draft) {
            return null;
        }
    }

    private static WorkItemEntity workItem(String id, String status) {
        WorkItemEntity e = new WorkItemEntity();
        e.setId(id);
        e.setProjectId(PID);
        e.setRequirementId("r1");
        e.setSeq(1L);
        e.setType(WorkItemEntity.TYPE_DEVELOPMENT);
        e.setTitle("WI " + id);
        e.setStatus(status);
        return e;
    }

    private static ProjectEntity project(Boolean autoBuild, Boolean autoDeploy) {
        ProjectEntity e = new ProjectEntity();
        e.setId(PID);
        e.setAutoBuildOnWiDone(autoBuild);
        e.setAutoDeployTestOnBuild(autoDeploy);
        return e;
    }

    private static SimpleDomainEvent wiDoneEvent() {
        return SimpleDomainEvent.of("workitem.status.changed", PID, WI_ID, "tester", "done", null, null, null);
    }

    private static SimpleDomainEvent buildCompletedEvent(Long buildId, Boolean success) {
        return SimpleDomainEvent.of("build.completed", PID, WI_ID, "tester", "build done",
                "BUILD", buildId == null ? null : String.valueOf(buildId), success);
    }

    // ---------------- FR-02 构建闸门 ----------------

    @Test
    void buildSwitchOffSkipsAutoBuild() {
        projects.put(PID, project(false, false));
        orchestrator.onWorkItemStatusChanged(wiDoneEvent());
        assertTrue(buildService.triggers.isEmpty(), "开关关不得触发构建");
    }

    @Test
    void buildSwitchNullDefaultsOff() {
        projects.put(PID, project(null, null)); // 存量行 null = 默认关
        orchestrator.onWorkItemStatusChanged(wiDoneEvent());
        assertTrue(buildService.triggers.isEmpty(), "存量行 null 应按关处理");
    }

    @Test
    void missingProjectRowTreatedAsOff() {
        orchestrator.onWorkItemStatusChanged(wiDoneEvent());
        assertTrue(buildService.triggers.isEmpty(), "项目行不存在按关处理（安全侧）");
    }

    @Test
    void buildSwitchOnTriggersBuild() {
        projects.put(PID, project(true, false));
        orchestrator.onWorkItemStatusChanged(wiDoneEvent());
        assertEquals(1, buildService.triggers.size());
        assertEquals(WI_ID, buildService.triggers.get(0).workItemId());
    }

    @Test
    void retrySweepRespectsBuildSwitch() {
        // 补扫路径：build.completed 后重扫 DONE 且无构建记录的 WI，同样受闸门管住
        projects.put(PID, project(false, false));
        orchestrator.onBuildCompleted(buildCompletedEvent(null, false));
        assertTrue(buildService.triggers.isEmpty(), "补扫不得绕过开关");

        projects.put(PID, project(true, false));
        orchestrator.onBuildCompleted(buildCompletedEvent(null, false));
        assertEquals(1, buildService.triggers.size(), "打开后补扫应补触发");
    }

    // ---------------- CAP-100 FR-04 需求级构建幂等 ----------------

    private static BuildEntity buildOf(long id, String workItemId) {
        BuildEntity b = new BuildEntity();
        b.setId(id);
        b.setProjectId(PID);
        b.setWorkItemId(workItemId);
        return b;
    }

    private static SimpleDomainEvent wiDoneEvent(String workItemId) {
        return SimpleDomainEvent.of("workitem.status.changed", PID, workItemId, "tester", "done",
                null, null, null);
    }

    @Test
    void sameRequirementSecondWiDoneDoesNotBuildAgain() {
        // 批量自动 DONE 场景：同需求已有构建记录 → 第二个 WI DONE 不再触发（N 次压成 1 次）
        projects.put(PID, project(true, false));
        builds.put(1L, buildOf(1L, WI_ID));
        workItems.put("wi-2", workItem("wi-2", WorkItemEntity.STATUS_DONE));

        orchestrator.onWorkItemStatusChanged(wiDoneEvent("wi-2"));

        assertTrue(buildService.triggers.isEmpty(), "同需求已有构建记录，第二个 WI 不自动构建");
    }

    @Test
    void retrySweepSkipsWiWhoseRequirementAlreadyBuilt() {
        // 补扫同口径：同需求已构建过的 DONE WI 不再补触发
        projects.put(PID, project(true, false));
        builds.put(1L, buildOf(1L, WI_ID));
        workItems.put("wi-2", workItem("wi-2", WorkItemEntity.STATUS_DONE));

        orchestrator.onBuildCompleted(buildCompletedEvent(1L, false));

        assertTrue(buildService.triggers.isEmpty(), "同需求已构建，补扫不得给其余 WI 补触发");
    }

    @Test
    void differentRequirementWiStillBuilds() {
        // 需求级幂等不误伤：其它需求的 WI DONE 照常触发
        projects.put(PID, project(true, false));
        builds.put(1L, buildOf(1L, WI_ID));
        WorkItemEntity other = workItem("wi-3", WorkItemEntity.STATUS_DONE);
        other.setRequirementId("r2");
        workItems.put("wi-3", other);

        orchestrator.onWorkItemStatusChanged(wiDoneEvent("wi-3"));

        assertEquals(1, buildService.triggers.size());
        assertEquals("wi-3", buildService.triggers.get(0).workItemId());
    }

    // ---------------- FR-02 部署闸门 ----------------

    @Test
    void deploySwitchOffSkipsAutoDeploy() {
        projects.put(PID, project(false, false));
        primeDeployPrereqs();
        orchestrator.onBuildCompleted(buildCompletedEvent(1L, true));
        assertTrue(deploymentService.creates.isEmpty(), "开关关不得自动部署");
    }

    @Test
    void deploySwitchOnAutoDeploys() {
        projects.put(PID, project(false, true));
        primeDeployPrereqs();
        orchestrator.onBuildCompleted(buildCompletedEvent(1L, true));
        assertEquals(1, deploymentService.creates.size());
        assertEquals(List.of(7L), deploymentService.executes);
    }

    /** 部署链路前置：构建带产物 + TEST 环境 + 部署步骤（闸门之后才会读到）。 */
    private void primeDeployPrereqs() {
        BuildEntity b = new BuildEntity();
        b.setId(1L);
        b.setProjectId(PID);
        b.setWorkItemId(WI_ID);
        b.setArtifactRef("pkg/app.tgz");
        builds.put(1L, b);
        EnvironmentEntity test = new EnvironmentEntity();
        test.setId(1L);
        test.setName(EnvironmentEntity.TEST);
        envs.add(test);
        deployConfig = new DeployConfigEntity();
        deployConfig.setStepsJson("[{\"name\":\"up\"}]");
    }
}
