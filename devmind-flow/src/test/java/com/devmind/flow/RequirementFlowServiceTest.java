package com.devmind.flow;

import com.devmind.artifact.ArtifactService;
import com.devmind.auth.IdentityService;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.DesignService;
import com.devmind.project.RelationService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.DesignRequest;
import com.devmind.project.dto.DesignView;
import com.devmind.project.dto.RelationRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.DesignEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionManagerService;
import com.devmind.session.service.SessionOutputService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequirementFlowService 流程编排单测（无 Spring 上下文，依赖全部子类 fake / 接口代理）。
 * 覆盖 CAP-38：阶段跳过（幂等/状态推进/已完成 409）、方案产出自动拆分（门禁降级）、
 * 拆分产出自动固化（建 WI+depends_on 边+事件通知）与产出缺失降级。
 */
class RequirementFlowServiceTest {

    private FakeRequirementService requirementService;
    private FakeWorkItemService workItemService;
    private FakeDesignService designService;
    private FakeRelationService relationService;
    private FakeSessionManager sessionManager;
    private FakeSessionOutputService sessionOutputService;
    private FakeDocumentService documentService;
    private FakeNotificationService notificationService;
    private FakeEventPublisher eventPublisher;
    private Map<String, SessionEntity> sessionStore;
    private RequirementFlowService service;

    // ---------------- fakes ----------------

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new LinkedHashMap<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        RequirementEntity add(String id, String status) {
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId("p1");
            e.setSeq(1L);
            e.setTitle("需求" + id);
            e.setStatus(status);
            store.put(id, e);
            return e;
        }

        @Override
        public RequirementEntity requireEntity(String projectId, String requirementId) {
            return requireById(requirementId);
        }

        @Override
        public RequirementEntity requireById(String requirementId) {
            RequirementEntity e = store.get(requirementId);
            if (e == null) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
            }
            return e;
        }

        @Override
        public RequirementView updateStatus(String projectId, String requirementId, String status) {
            requireById(requirementId).setStatus(status);
            return null;
        }

        @Override
        public void saveStageFlags(RequirementEntity e) {
            // 内存 fake：实体已是引用共享，无需落库
        }
    }

    static class FakeWorkItemService extends WorkItemService {
        final Map<String, WorkItemEntity> store = new LinkedHashMap<>();
        private int seq = 0;

        FakeWorkItemService() {
            super(null, null, null, null, null, null);
        }

        WorkItemEntity add(String id, String type, String status) {
            WorkItemEntity e = new WorkItemEntity();
            e.setId(id);
            e.setProjectId("p1");
            e.setRequirementId("r1");
            e.setType(type);
            e.setTitle("WI " + id);
            e.setSpec("spec of " + id);
            e.setStatus(status);
            store.put(id, e);
            return e;
        }

        @Override
        public List<WorkItemView> list(String projectId, String requirementId) {
            return store.values().stream()
                    .filter(e -> requirementId.equals(e.getRequirementId()))
                    .map(this::toView).toList();
        }

        @Override
        public synchronized WorkItemView create(String projectId, String requirementId, WorkItemRequest req) {
            WorkItemEntity e = new WorkItemEntity();
            e.setId("wi-new-" + (++seq));
            e.setProjectId(projectId);
            e.setRequirementId(requirementId);
            e.setType(req.type());
            e.setTitle(req.title());
            e.setSpec(req.spec());
            e.setDesignId(req.designId());
            e.setStatus(WorkItemEntity.STATUS_TODO);
            store.put(e.getId(), e);
            return toView(e);
        }

        @Override
        public WorkItemView updateStatus(String projectId, String requirementId, String workItemId, String status) {
            WorkItemEntity e = requireById(workItemId);
            e.setStatus(status);
            return toView(e);
        }

        @Override
        public WorkItemEntity requireEntity(String projectId, String workItemId) {
            return requireById(workItemId);
        }

        @Override
        public WorkItemEntity requireById(String workItemId) {
            WorkItemEntity e = store.get(workItemId);
            if (e == null) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "工作单元不存在: " + workItemId);
            }
            return e;
        }

        private WorkItemView toView(WorkItemEntity e) {
            return new WorkItemView(e.getId(), e.getProjectId(), e.getRequirementId(), e.getDesignId(),
                    1L, "WI-1", e.getType(), e.getTitle(), e.getSpec(), e.getStatus(),
                    null, null, "test", Instant.now(), Instant.now());
        }
    }

    static class FakeDesignService extends DesignService {
        final List<DesignView> designs = new ArrayList<>();
        private int seq = 0;

        FakeDesignService() {
            super(null, null, null);
        }

        @Override
        public List<DesignView> list(String projectId, String requirementId) {
            return designs;
        }

        @Override
        public DesignView create(String projectId, String requirementId, DesignRequest req) {
            DesignView v = new DesignView("d" + (++seq), projectId, requirementId, req.docId(),
                    seq, DesignEntity.STATUS_DRAFT, "test", Instant.now(), Instant.now());
            designs.add(v);
            return v;
        }
    }

    static class FakeRelationService extends RelationService {
        final List<RelationRequest> created = new ArrayList<>();

        FakeRelationService() {
            super(null, null);
        }

        @Override
        public com.devmind.project.dto.RelationView create(String projectId, RelationRequest req) {
            created.add(req);
            return null;
        }

        /** CAP-52：开发会话清单要把 depends_on 边渲染成「先完成 #n」，读的就是这个方法。 */
        @Override
        public List<com.devmind.project.dto.RelationView> list(String projectId, String fromType, String fromId) {
            return created.stream()
                    .filter(r -> fromType == null || fromType.equals(r.fromType()))
                    .filter(r -> fromId == null || fromId.equals(r.fromId()))
                    .map(r -> new com.devmind.project.dto.RelationView("rel-" + created.indexOf(r), projectId,
                            r.fromType(), r.fromId(), r.toType(), r.toId(), r.relationType(), Instant.now()))
                    .toList();
        }
    }

    static class FakeSessionManager extends SessionManagerService {
        final List<CreateSessionRequest> requests = new ArrayList<>();
        private int seq = 0;

        FakeSessionManager() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public SessionView create(CreateSessionRequest req) {
            requests.add(req);
            return new SessionView("sess-new-" + (++seq), req.projectId(), req.workItemId(), req.requirementId(),
                    req.taskSpec(), "QUEUED", null, null, null, null, null, null, null, null, null,
                    Instant.now(), Instant.now(), null);
        }
    }

    static class FakeSessionOutputService extends SessionOutputService {
        final Map<String, String> outputs = new HashMap<>(); // sessionId:fileName -> content

        FakeSessionOutputService() {
            super(null);
        }

        void put(String sessionId, String fileName, String content) {
            outputs.put(sessionId + ":" + fileName, content);
        }

        @Override
        public Optional<String> findContent(String sessionId, String fileName) {
            return Optional.ofNullable(outputs.get(sessionId + ":" + fileName));
        }
    }

    static class FakeDocumentService extends DocumentService {
        final List<DocDetail> docs = new ArrayList<>();
        private long seq = 0;

        FakeDocumentService() {
            super(null, null, null, null, null, null, null);
        }

        DocDetail add(String kind, String requirementId, String content) {
            DocDetail d = new DocDetail(++seq, kind, requirementId, null, "p1", kind + " 文档",
                    1, "PUBLISHED", List.of(), content, null, "test",
                    Instant.now(), Instant.now());
            docs.add(d);
            return d;
        }

        @Override
        public Optional<DocDetail> findLatestByKind(String requirementId, String kind) {
            return docs.stream()
                    .filter(d -> kind.equals(d.kind()) && requirementId.equals(d.requirementId()))
                    .reduce((a, b) -> b);
        }

        @Override
        public DocDetail create(DocRequest req) {
            return add(req.kind(), req.requirementId(), req.contentMd());
        }

        @Override
        public DocDetail saveVersion(Long id, SaveVersionRequest req) {
            DocDetail old = docs.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
            DocDetail d = new DocDetail(old.id(), old.kind(), old.requirementId(), old.workItemId(),
                    old.projectId(), old.title(), old.versionNo() + 1, old.status(), old.tags(),
                    req.contentMd(), req.changeNote(), old.createdBy(),
                    old.createdAt(), Instant.now());
            docs.remove(old);
            docs.add(d);
            return d;
        }

        @Override
        public DocDetail get(Long id, Integer version) {
            return docs.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
        }
    }

    static class FakeArtifactService extends ArtifactService {
        FakeArtifactService() {
            super(null, null);
        }

        @Override
        public com.devmind.artifact.dto.ArtifactView registerInfo(String projectId, String requirementId,
                                                                  String workItemId, String type, String name,
                                                                  String path, String producerType) {
            return null;
        }
    }

    static class FakeNotificationService extends NotificationService {
        final List<NotificationDraft> drafts = new ArrayList<>();

        FakeNotificationService() {
            super(null, null, null, null, null, List.of(), null);
        }

        @Override
        public com.devmind.notification.dto.NotificationView emit(NotificationDraft draft) {
            drafts.add(draft);
            return null;
        }

        List<NotificationDraft> ofType(String type) {
            return drafts.stream().filter(d -> type.equals(d.eventType())).toList();
        }
    }

    static class FakeEventPublisher extends DomainEventPublisher {
        final List<DomainEvent> events = new ArrayList<>();

        FakeEventPublisher() {
            super(null);
        }

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    static class FakeIdentityService extends IdentityService {
        FakeIdentityService() {
            super(null, null, null);
        }

        @Override
        public String currentActor() {
            return "tester";
        }
    }

    // ---------------- setup ----------------

    @BeforeEach
    void setUp() {
        requirementService = new FakeRequirementService();
        workItemService = new FakeWorkItemService();
        designService = new FakeDesignService();
        relationService = new FakeRelationService();
        sessionManager = new FakeSessionManager();
        sessionOutputService = new FakeSessionOutputService();
        documentService = new FakeDocumentService();
        notificationService = new FakeNotificationService();
        eventPublisher = new FakeEventPublisher();
        sessionStore = new HashMap<>();
        SessionRepository sessionRepo = proxy(SessionRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findById" -> Optional.ofNullable(sessionStore.get((String) args[0]));
                    // CAP-52 requireNoActiveSession：同需求会话互斥预检
                    case "findByRequirementIdOrderByCreatedAtDesc" -> sessionStore.values().stream()
                            .filter(s -> args[0].equals(s.getRequirementId()))
                            .toList();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        service = new RequirementFlowService(requirementService, workItemService, designService,
                relationService, sessionManager, sessionRepo, sessionOutputService, documentService,
                new FakeArtifactService(), notificationService, eventPublisher, new ObjectMapper(),
                new FakeIdentityService());
    }

    private SessionEntity addSession(String id, String workItemId, String requirementId, String taskSpec) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setProjectId("p1");
        s.setWorkItemId(workItemId);
        s.setRequirementId(requirementId);
        s.setTaskSpec(taskSpec);
        sessionStore.put(id, s);
        return s;
    }

    /** 触发会话完成分流（生产走 flowExecutor 异步，测试同步直调本体）。 */
    private void fireCompleted(String sessionId) {
        service.handleCompleted(sessionId);
    }

    // ---------------- 方案产出自动拆分 ----------------

    @Test
    void 方案产出_登记方案并自动起拆分会话() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        workItemService.add("wi-design", WorkItemEntity.TYPE_DESIGN, WorkItemEntity.STATUS_IN_PROGRESS);
        addSession("s1", "wi-design", "r1", "方案设计任务");
        sessionOutputService.put("s1", FlowOutputContract.DESIGN_FILE, "# 方案 v1");

        fireCompleted("s1");

        // 方案已登记（文档 + Design）
        assertEquals(1, designService.designs.size());
        assertEquals(1, documentService.docs.stream().filter(d -> "design".equals(d.kind())).count());
        // DESIGN WI 置 DONE（让 rollup 离开 DESIGNING）
        assertEquals(WorkItemEntity.STATUS_DONE, workItemService.requireById("wi-design").getStatus());
        // 自动起拆分会话（[flow:split] 标记 + 注入方案内容）
        assertEquals(1, sessionManager.requests.size());
        CreateSessionRequest split = sessionManager.requests.get(0);
        assertTrue(split.taskSpec().startsWith(FlowOutputContract.MARKER_SPLIT));
        assertTrue(split.taskSpec().contains("# 方案 v1"));
        assertEquals("r1", split.requirementId());
        assertNull(split.workItemId());
        // 通知：方案已生成（含自动拆分说明）
        assertEquals(1, notificationService.ofType("flow.design.ready").size());
    }

    @Test
    void 方案产出_有进行中执行WI不自动拆分() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        workItemService.add("wi-design", WorkItemEntity.TYPE_DESIGN, WorkItemEntity.STATUS_IN_PROGRESS);
        workItemService.add("wi-dev", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_IN_PROGRESS);
        addSession("s1", "wi-design", "r1", "方案设计任务");
        sessionOutputService.put("s1", FlowOutputContract.DESIGN_FILE, "# 方案 v1");

        fireCompleted("s1");

        // 方案仍登记，但不起拆分会话，发降级通知
        assertEquals(1, designService.designs.size());
        assertEquals(0, sessionManager.requests.size());
        assertEquals(1, notificationService.ofType("flow.split.deferred").size());
    }

    // ---------------- 拆分产出自动固化 ----------------

    @Test
    void 拆分产出_自动固化建WI与依赖边() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        addSession("s2", null, "r1", FlowOutputContract.MARKER_SPLIT + "\n拆分任务");
        sessionOutputService.put("s2", FlowOutputContract.WI_PLAN_FILE,
                """
                [
                  {"type":"DEVELOPMENT","title":"后端接口","spec":"实现 REST","dependsOn":[]},
                  {"type":"TEST","title":"接口测试","spec":"补测试","dependsOn":[0]}
                ]
                """);

        fireCompleted("s2");

        // 固化 2 个 WI（CAP-52：固化即交给唯一的开发会话，故直接 IN_PROGRESS）
        List<WorkItemView> items = workItemService.list("p1", "r1");
        assertEquals(2, items.size());
        assertEquals("后端接口", items.get(0).title());
        assertEquals(WorkItemEntity.STATUS_IN_PROGRESS, items.get(1).status());
        // depends_on 边：第 2 项依赖第 1 项
        assertEquals(1, relationService.created.size());
        RelationRequest edge = relationService.created.get(0);
        assertEquals("depends_on", edge.relationType());
        assertEquals(items.get(1).id(), edge.fromId());
        assertEquals(items.get(0).id(), edge.toId());
        // 编排器事件 + 完成通知 + 需求级开发会话（一个会话吃整份清单，依赖渲染成人话）
        assertEquals(1, eventPublisher.events.stream().filter(e -> "flow.split.confirmed".equals(e.type())).count());
        assertEquals(1, notificationService.ofType("flow.split.done").size());
        assertEquals(1, notificationService.ofType("flow.dispatched").size());
        assertEquals(1, sessionManager.requests.size());
        CreateSessionRequest dev = sessionManager.requests.get(0);
        assertTrue(dev.taskSpec().startsWith(FlowOutputContract.MARKER_DEV));
        assertNull(dev.workItemId());
        assertEquals("r1", dev.requirementId());
        assertTrue(dev.taskSpec().contains("后端接口"));
        assertTrue(dev.taskSpec().contains("依赖：先完成 #1"), dev.taskSpec());
    }

    @Test
    void 拆分产出_产出缺失降级通知不建WI() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        addSession("s2", null, "r1", FlowOutputContract.MARKER_SPLIT + "\n拆分任务");

        fireCompleted("s2");

        assertEquals(0, workItemService.list("p1", "r1").size());
        assertEquals(1, notificationService.ofType("flow.split.missing").size());
        assertEquals(0, eventPublisher.events.size());
    }

    @Test
    void 拆分产出_清单非法降级通知不建WI() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        addSession("s2", null, "r1", FlowOutputContract.MARKER_SPLIT + "\n拆分任务");
        sessionOutputService.put("s2", FlowOutputContract.WI_PLAN_FILE,
                """
                [
                  {"type":"DEVELOPMENT","title":"A","spec":"a","dependsOn":[1]},
                  {"type":"DEVELOPMENT","title":"B","spec":"b","dependsOn":[0]}
                ]
                """);

        fireCompleted("s2");

        // 环依赖校验失败 → 降级人工
        assertEquals(0, workItemService.list("p1", "r1").size());
        assertEquals(1, notificationService.ofType("flow.split.missing").size());
    }

    // ---------------- CAP-52：三合一规划会话 ----------------

    @Test
    void 规划产出_三文件齐备登记并自动起开发会话() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        addSession("s3", null, "r1", FlowOutputContract.MARKER_PLAN + "\n规划任务");
        sessionOutputService.put("s3", FlowOutputContract.ANALYSIS_FILE, "# 分析");
        sessionOutputService.put("s3", FlowOutputContract.DESIGN_FILE, "# 方案");
        sessionOutputService.put("s3", FlowOutputContract.WI_PLAN_FILE,
                """
                [
                  {"type":"DEVELOPMENT","title":"改造接口","spec":"改哪里/怎么验收","dependsOn":[]},
                  {"type":"TEST","title":"补测试","spec":"覆盖新分支","dependsOn":[0]}
                ]
                """);

        fireCompleted("s3");

        // 三份产出各自落库（一个会话 → 分析文档 + 方案文档/Design + 2 个 WI）
        assertEquals(1, documentService.docs.stream().filter(d -> "analysis".equals(d.kind())).count());
        assertEquals(1, documentService.docs.stream().filter(d -> "design".equals(d.kind())).count());
        assertEquals(1, designService.designs.size());
        assertEquals(2, workItemService.list("p1", "r1").size());
        // 一次完成通知 + 一次自动派发通知，且只起一个开发会话
        assertEquals(1, notificationService.ofType("flow.plan.done").size());
        assertEquals(1, notificationService.ofType("flow.dispatched").size());
        assertEquals(0, notificationService.ofType("flow.plan.partial").size());
        assertEquals(1, sessionManager.requests.size());
        assertTrue(sessionManager.requests.get(0).taskSpec().startsWith(FlowOutputContract.MARKER_DEV));
    }

    @Test
    void 规划产出_缺方案只登记其余并发部分产出通知() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        addSession("s3", null, "r1", FlowOutputContract.MARKER_PLAN + "\n规划任务");
        sessionOutputService.put("s3", FlowOutputContract.ANALYSIS_FILE, "# 分析");
        sessionOutputService.put("s3", FlowOutputContract.WI_PLAN_FILE,
                """
                [{"type":"DEVELOPMENT","title":"A","spec":"a","dependsOn":[]}]
                """);

        fireCompleted("s3");

        // 缺一个文件不拖垮其余：分析与清单照常落库，只是不起开发会话
        assertEquals(1, documentService.docs.stream().filter(d -> "analysis".equals(d.kind())).count());
        assertEquals(1, workItemService.list("p1", "r1").size());
        assertEquals(1, notificationService.ofType("flow.plan.partial").size());
        assertTrue(notificationService.ofType("flow.plan.partial").get(0).body().contains("design.md"));
        assertEquals(0, sessionManager.requests.size());
    }

    @Test
    void 规划产出_跳过阶段不要求对应文件() {
        RequirementEntity req = requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        req.setAnalysisSkipped(true);
        req.setDesignSkipped(true);
        addSession("s3", null, "r1", FlowOutputContract.MARKER_PLAN + "\n规划任务");
        sessionOutputService.put("s3", FlowOutputContract.WI_PLAN_FILE,
                """
                [{"type":"DEVELOPMENT","title":"A","spec":"a","dependsOn":[]}]
                """);

        fireCompleted("s3");

        assertEquals(1, notificationService.ofType("flow.plan.done").size());
        assertEquals(1, sessionManager.requests.size());
    }

    @Test
    void 拆分粒度过细_超上限拒绝固化() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        addSession("s2", null, "r1", FlowOutputContract.MARKER_SPLIT + "\n拆分任务");
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < SplitPlanValidator.MAX_ITEMS + 1; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"type\":\"DEVELOPMENT\",\"title\":\"T").append(i)
                    .append("\",\"spec\":\"s\",\"dependsOn\":[]}");
        }
        sessionOutputService.put("s2", FlowOutputContract.WI_PLAN_FILE, json.append(']').toString());

        fireCompleted("s2");

        assertEquals(0, workItemService.list("p1", "r1").size());
        assertEquals(1, notificationService.ofType("flow.split.missing").size());
        assertTrue(notificationService.ofType("flow.split.missing").get(0).body().contains("上限"));
        assertEquals(0, sessionManager.requests.size());
    }

    // ---------------- CAP-52：需求级开发会话 ----------------

    @Test
    void 开发产出_需求置待验收且不自动完成工作单元() {
        requirementService.add("r1", RequirementEntity.STATUS_IN_PROGRESS);
        workItemService.add("wi-1", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_IN_PROGRESS);
        addSession("s4", null, "r1", FlowOutputContract.MARKER_DEV + "\n开发任务");
        sessionOutputService.put("s4", FlowOutputContract.DEV_SUMMARY_FILE, "## 完成情况\n- 都做完了");

        fireCompleted("s4");

        assertEquals(RequirementEntity.STATUS_ACCEPTANCE, requirementService.requireById("r1").getStatus());
        // 人验收才置 DONE：N 条一起自动 DONE 会掀起 N 次构建
        assertEquals(WorkItemEntity.STATUS_IN_PROGRESS, workItemService.requireById("wi-1").getStatus());
        assertEquals(1, notificationService.ofType("flow.dev.done").size());
    }

    @Test
    void 开发产出_缺摘要仍待验收并提示核对() {
        requirementService.add("r1", RequirementEntity.STATUS_IN_PROGRESS);
        addSession("s4", null, "r1", FlowOutputContract.MARKER_DEV + "\n开发任务");

        fireCompleted("s4");

        assertEquals(RequirementEntity.STATUS_ACCEPTANCE, requirementService.requireById("r1").getStatus());
        assertTrue(notificationService.ofType("flow.dev.done").get(0).body().contains("dev-summary.md"));
    }

    // ---------------- CAP-52：入口收敛 ----------------

    @Test
    void 规划入口_一个会话产出范围含三份且DRAFT推进ANALYZING() {
        RequirementEntity req = requirementService.add("r1", RequirementEntity.STATUS_DRAFT);

        SessionView view = service.startPlan("p1", "r1");

        assertTrue(view.taskSpec().startsWith(FlowOutputContract.MARKER_PLAN));
        assertTrue(view.taskSpec().contains(FlowOutputContract.ANALYSIS_FILE));
        assertTrue(view.taskSpec().contains(FlowOutputContract.DESIGN_FILE));
        assertTrue(view.taskSpec().contains(FlowOutputContract.WI_PLAN_FILE));
        assertEquals(RequirementEntity.STATUS_ANALYZING, req.getStatus());
        assertTrue(notificationService.drafts.isEmpty()); // 起会话本身不发通知，产出后才有
        assertNull(view.workItemId());
    }

    @Test
    void 规划入口_跳过分析时产出范围不含分析文件() {
        RequirementEntity req = requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        req.setAnalysisSkipped(true);

        SessionView view = service.startPlan("p1", "r1");

        assertTrue(view.taskSpec().startsWith(FlowOutputContract.MARKER_PLAN));
        assertTrue(!view.taskSpec().contains(FlowOutputContract.ANALYSIS_FILE));
        assertTrue(view.taskSpec().contains(FlowOutputContract.DESIGN_FILE));
    }

    @Test
    void 规划入口_已有进行中会话报409() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        SessionEntity active = addSession("s9", null, "r1", "在跑");
        active.setStatus(SessionState.RUNNING.name());

        DevMindException e = assertThrows(DevMindException.class, () -> service.startPlan("p1", "r1"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertEquals(0, sessionManager.requests.size());
    }

    @Test
    void 规划入口_需求终态报409() {
        requirementService.add("r1", RequirementEntity.STATUS_DONE);

        DevMindException e = assertThrows(DevMindException.class, () -> service.startPlan("p1", "r1"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void 开发入口_无工作单元报409() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);

        DevMindException e = assertThrows(DevMindException.class, () -> service.startDev("p1", "r1"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("开启 AI 规划"));
    }

    @Test
    void 开发入口_按清单起会话并把TODO置进行中() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        workItemService.add("wi-1", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_TODO);
        workItemService.add("wi-2", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_DONE);

        SessionView view = service.startDev("p1", "r1");

        assertTrue(view.taskSpec().startsWith(FlowOutputContract.MARKER_DEV));
        assertEquals(WorkItemEntity.STATUS_IN_PROGRESS, workItemService.requireById("wi-1").getStatus());
        // 已完成的不回退
        assertEquals(WorkItemEntity.STATUS_DONE, workItemService.requireById("wi-2").getStatus());
    }

    // ---------------- CAP-39 publishOutput ----------------

    @Test
    void 推送创建分析文档() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        addSession("s1", null, "r1", "任意任务");
        sessionOutputService.put("s1", "analysis.md", "# 分析结论");

        var result = service.publishOutput("s1", new com.devmind.flow.dto.PublishOutputRequest(
                "analysis.md", "analysis", "r1", "create", null, null, null));

        assertEquals(1, result.versionNo());
        assertNull(result.designId());
        DocDetail doc = documentService.get(result.docId(), null);
        assertEquals("analysis", doc.kind());
        assertEquals("r1", doc.requirementId());
        assertEquals("# 分析结论", doc.contentMd());
        assertTrue(designService.designs.isEmpty());
    }

    @Test
    void 推送创建方案文档同步落Design草稿() {
        requirementService.add("r1", RequirementEntity.STATUS_DESIGNING);
        addSession("s1", "wi1", "r1", "方案任务");
        sessionOutputService.put("s1", "design.md", "# 方案");

        var result = service.publishOutput("s1", new com.devmind.flow.dto.PublishOutputRequest(
                "design.md", "design", "r1", "create", null, null, null));

        assertEquals(1, designService.designs.size());
        assertEquals(result.designId(), designService.designs.get(0).id());
        assertEquals(result.docId(), designService.designs.get(0).docId());
        assertEquals(DesignEntity.STATUS_DRAFT, designService.designs.get(0).status());
    }

    @Test
    void 推送更新既有文档存新版本带默认说明() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        addSession("s1", null, "r1", "任务");
        sessionOutputService.put("s1", "analysis.md", "v2 内容");
        DocDetail existing = documentService.add("analysis", "r1", "旧内容");

        var result = service.publishOutput("s1", new com.devmind.flow.dto.PublishOutputRequest(
                "analysis.md", "analysis", "r1", "update", existing.id(), null, null));

        assertEquals(2, result.versionNo());
        DocDetail doc = documentService.get(existing.id(), null);
        assertEquals("v2 内容", doc.contentMd());
        assertEquals("手动推送自会话 s1", doc.changeNote());
        assertEquals(1, documentService.docs.size(), "更新不新建第二份文档");
    }

    @Test
    void 推送更新跨需求或类型不匹配400() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        requirementService.add("r2", RequirementEntity.STATUS_DRAFT);
        addSession("s1", null, "r1", "任务");
        sessionOutputService.put("s1", "analysis.md", "内容");
        DocDetail otherReq = documentService.add("analysis", "r2", "别需求的");

        DevMindException e = assertThrows(DevMindException.class, () -> service.publishOutput("s1",
                new com.devmind.flow.dto.PublishOutputRequest(
                        "analysis.md", "analysis", "r1", "update", otherReq.id(), null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());

        DocDetail otherKind = documentService.add("design", "r1", "类型不符");
        e = assertThrows(DevMindException.class, () -> service.publishOutput("s1",
                new com.devmind.flow.dto.PublishOutputRequest(
                        "analysis.md", "analysis", "r1", "update", otherKind.id(), null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 推送产出缺失404_类型非法400() {
        requirementService.add("r1", RequirementEntity.STATUS_ANALYZING);
        addSession("s1", null, "r1", "任务");

        DevMindException e = assertThrows(DevMindException.class, () -> service.publishOutput("s1",
                new com.devmind.flow.dto.PublishOutputRequest(
                        "ghost.md", "analysis", "r1", "create", null, null, null)));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());

        sessionOutputService.put("s1", "a.md", "内容");
        e = assertThrows(DevMindException.class, () -> service.publishOutput("s1",
                new com.devmind.flow.dto.PublishOutputRequest(
                        "a.md", "wiki", "r1", "create", null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }
}
