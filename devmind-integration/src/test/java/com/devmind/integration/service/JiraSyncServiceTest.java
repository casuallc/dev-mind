package com.devmind.integration.service;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.dto.JiraSyncRunView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.JiraSyncConfigEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.JiraSyncConfigRepository;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.JiraManagedFields;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JiraSyncService 同步闭环单测（无 Spring 上下文）：
 * repo 用 JDK 动态代理内存 fake，RequirementService/IntegrationService 子类覆盖，
 * connector 喂队列页面。覆盖：导入（需求时间取 issue 自身）/幂等重放/托管字段刷新
 * （本地字段不动）/失败收敛/分页；JQL 只含 project + 附加片段，无其他过滤。
 */
class JiraSyncServiceTest {

    private static final Instant T1 = Instant.parse("2026-08-28T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-29T02:00:00Z");

    private JiraSyncConfigEntity cfg;
    private IntegrationEntity integration;
    private FakeConnector connector;
    private FakeRequirementService requirementService;
    private FakeIntegrationService integrationService;
    private FakeEventPublisher eventPublisher;
    private Map<String, ExternalLinkEntity> linkStore;
    private JiraSyncService service;

    // ---------------- fakes ----------------

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeConnector implements IntegrationConnector {
        Queue<IssuePage> pages = new ArrayDeque<>();
        RuntimeException failure;

        @Override public String type() { return IntegrationEntity.TYPE_JIRA; }
        @Override public TestResult testConnection(IntegrationEntity c, String t) { throw new UnsupportedOperationException(); }
        @Override public List<ExternalProject> listProjects(IntegrationEntity c, String t) { throw new UnsupportedOperationException(); }
        @Override public MergeRequestRef createMergeRequest(IntegrationEntity c, String t, MrSpec s) { throw new UnsupportedOperationException(); }
        @Override public ReleaseRef createRelease(IntegrationEntity c, String t, ReleaseSpec s) { throw new UnsupportedOperationException(); }

        @Override
        public IssuePage searchIssues(IntegrationEntity c, String token, IssueQuery query) {
            if (failure != null) {
                throw failure;
            }
            return pages.isEmpty()
                    ? new IssuePage(query.startAt(), query.maxResults(), query.startAt(), List.of())
                    : pages.poll();
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();
        private int seq = 0;

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null);
        }

        /** 与 RequirementService.applyJiraFields 同款的 fake 落库（标题截 240） */
        private static void applyJira(RequirementEntity e, JiraManagedFields f) {
            String title = f.title() == null ? "" : f.title().trim();
            e.setTitle(title.length() > 240 ? title.substring(0, 240) : title);
            e.setDescription(f.description());
            e.setType(f.type());
            e.setPriority(f.priority());
            e.setAssignee(f.assignee());
            e.setReporter(f.reporter());
            e.setLabels(f.labels() == null || f.labels().isEmpty() ? null : String.join(",", f.labels()));
            e.setFixVersions(f.fixVersions() == null || f.fixVersions().isEmpty()
                    ? null : String.join(",", f.fixVersions()));
            e.setDueDate(f.dueDate());
            e.setExternalKey(f.externalKey());
            e.setEstimatedSeconds(f.estimatedSeconds());
            e.setSpentSeconds(f.spentSeconds());
            // 与真实通道一致：创建/更新时间取 issue 自身
            e.setCreatedAt(f.issueCreated());
            e.setUpdatedAt(f.issueUpdated());
        }

        @Override
        public synchronized RequirementView createFromJira(String projectId, JiraManagedFields f) {
            String id = "req-" + (++seq);
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId(projectId);
            e.setStatus(RequirementEntity.STATUS_DRAFT);
            e.setSource(RequirementEntity.SOURCE_JIRA);
            applyJira(e, f);
            store.put(id, e);
            return toView(e);
        }

        @Override
        public RequirementView syncFromJira(String projectId, String requirementId, JiraManagedFields f) {
            RequirementEntity e = requireEntity(projectId, requirementId);
            applyJira(e, f);
            return toView(e);
        }

        @Override
        public RequirementEntity requireEntity(String projectId, String requirementId) {
            RequirementEntity e = store.get(requirementId);
            if (e == null || !projectId.equals(e.getProjectId())) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
            }
            return e;
        }

        private RequirementView toView(RequirementEntity e) {
            return new RequirementView(e.getId(), e.getProjectId(), 1L, "REQ-1", e.getTitle(),
                    e.getDescription(), e.getStatus(), e.getType(), null, null,
                    e.getSource(), e.getPriority(), e.getAssignee(), e.getReporter(),
                    List.of(), List.of(), e.getDueDate(), e.getExternalKey(), null, null,
                    null, e.getEstimatedSeconds(), e.getSpentSeconds(),
                    "test", Instant.now(), Instant.now());
        }
    }

    static class FakeIntegrationService extends IntegrationService {
        final IntegrationEntity integration;
        final List<Boolean> callOk = new ArrayList<>();

        FakeIntegrationService(IntegrationEntity integration) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, List.of());
            this.integration = integration;
        }

        @Override public String tokenOf(IntegrationEntity e) { return "fake-token"; }

        @Override
        public void recordCall(Long id, String action, String it, String ii, boolean ok, String err) {
            callOk.add(ok);
        }

        @Override public IntegrationEntity require(Long id) { return integration; }
    }

    static class FakeEventPublisher extends DomainEventPublisher {
        final List<DomainEvent> events = new ArrayList<>();

        FakeEventPublisher() {
            super(null);
        }

        @Override public void publish(DomainEvent event) { events.add(event); }
    }

    // ---------------- setup ----------------

    @BeforeEach
    void setUp() {
        integration = new IntegrationEntity();
        integration.setId(7L);
        integration.setType(IntegrationEntity.TYPE_JIRA);
        integration.setBaseUrl("http://jira.local");
        integration.setStatus(IntegrationEntity.STATUS_ENABLED);

        cfg = new JiraSyncConfigEntity();
        cfg.setId(1L);
        cfg.setIntegrationId(7L);
        cfg.setProjectId("p1");
        cfg.setJiraProjectKey("PROJ");
        cfg.setEnabled(true);

        connector = new FakeConnector();
        requirementService = new FakeRequirementService();
        integrationService = new FakeIntegrationService(integration);
        eventPublisher = new FakeEventPublisher();
        linkStore = new HashMap<>();

        Map<Long, JiraSyncConfigEntity> cfgStore = new HashMap<>(Map.of(1L, cfg));
        JiraSyncConfigRepository configRepo = proxy(JiraSyncConfigRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findById" -> Optional.ofNullable(cfgStore.get((Long) args[0]));
                    case "save" -> {
                        cfgStore.put(((JiraSyncConfigEntity) args[0]).getId(), (JiraSyncConfigEntity) args[0]);
                        yield args[0];
                    }
                    case "findByEnabledTrue" -> cfgStore.values().stream().filter(JiraSyncConfigEntity::isEnabled).toList();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        IntegrationRepository integrationRepo = proxy(IntegrationRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findById" -> Optional.of(integration);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        long[] linkSeq = {0};
        ExternalLinkRepository linkRepo = proxy(ExternalLinkRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findFirstByIntegrationIdAndExternalTypeAndExternalKeyOrderByIdDesc" ->
                            Optional.ofNullable(linkStore.get((String) args[2]));
                    case "save" -> {
                        ExternalLinkEntity link = (ExternalLinkEntity) args[0];
                        if (link.getId() == null) {
                            link.setId(++linkSeq[0]);
                        }
                        linkStore.put(link.getExternalKey(), link);
                        yield link;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        JiraSyncService[] holder = new JiraSyncService[1];
        ObjectProvider<JiraSyncService> self = new ObjectProvider<>() {
            @Override public JiraSyncService getObject() { return holder[0]; }
            @Override public Iterator<JiraSyncService> iterator() { return List.of(holder[0]).iterator(); }
        };
        holder[0] = new JiraSyncService(configRepo, integrationRepo, linkRepo, integrationService,
                null, requirementService, null, null, eventPublisher, List.of(connector), self);
        service = holder[0];
    }

    private IntegrationConnector.JiraIssue issue(String key, String summary, Instant updated) {
        return new IntegrationConnector.JiraIssue(key, summary, "描述 " + key, "Bug", "High",
                List.of("ai"), "Open", updated, updated, "张三",
                "李四", LocalDate.parse("2026-09-30"), List.of("1.0", "1.1"), 7200L, 3600L);
    }

    private IssuePage page(int startAt, int total, IntegrationConnector.JiraIssue... issues) {
        return new IssuePage(startAt, issues.length, total, List.of(issues));
    }

    // ---------------- 用例 ----------------

    @Test
    void 首轮导入建需求登记链接() {
        connector.pages.add(page(0, 2, issue("PROJ-1", "登录页报错", T1), issue("PROJ-2", "导出失败", T2)));

        JiraSyncRunView result = service.doSync(1L);

        assertEquals(2, result.imported());
        assertEquals(0, result.updated());
        assertEquals(1, result.pages());
        assertNull(result.error());
        // 需求：DRAFT + source=JIRA + 标题无前缀、描述无尾注 + 扩展字段落列
        assertEquals(2, requirementService.store.size());
        RequirementEntity r1 = requirementService.store.get("req-1");
        assertEquals("登录页报错", r1.getTitle());
        assertEquals("描述 PROJ-1", r1.getDescription());
        assertEquals(RequirementEntity.STATUS_DRAFT, r1.getStatus());
        assertEquals(RequirementEntity.SOURCE_JIRA, r1.getSource());
        assertEquals(RequirementEntity.TYPE_BUG, r1.getType());
        assertEquals("PROJ-1", r1.getExternalKey());
        assertEquals("High", r1.getPriority());
        assertEquals("李四", r1.getAssignee());
        assertEquals("张三", r1.getReporter());
        assertEquals("ai", r1.getLabels());
        assertEquals("1.0,1.1", r1.getFixVersions());
        assertEquals(LocalDate.parse("2026-09-30"), r1.getDueDate());
        // CAP-27：工时字段（time tracking 秒数）随同步落列
        assertEquals(7200L, r1.getEstimatedSeconds());
        assertEquals(3600L, r1.getSpentSeconds());
        // 创建/更新时间取 issue 自身（issue() 帮手 created=updated=T1）
        assertEquals(T1, r1.getCreatedAt());
        assertEquals(T1, r1.getUpdatedAt());
        assertEquals(T2, requirementService.store.get("req-2").getCreatedAt());
        // 链接：REQUIREMENT ↔ ISSUE
        ExternalLinkEntity link = linkStore.get("PROJ-1");
        assertEquals(ExternalLinkEntity.INTERNAL_REQUIREMENT, link.getInternalType());
        assertEquals("req-1", link.getInternalId());
        assertEquals("Open", link.getStatus());
        assertEquals("http://jira.local/browse/PROJ-1", link.getExternalUrl());
        // 运行状态落库
        assertNotNull(cfg.getLastSyncAt());
        assertNull(cfg.getLastError());
        // 审计 + 事件
        assertEquals(List.of(true), integrationService.callOk);
        assertEquals(1, eventPublisher.events.size());
        assertEquals("integration.jira.synced", eventPublisher.events.get(0).type());
    }

    @Test
    void 幂等重放不重复建需求() {
        connector.pages.add(page(0, 2, issue("PROJ-1", "登录页报错", T1), issue("PROJ-2", "导出失败", T2)));
        service.doSync(1L);
        // 第二轮拉同一页（重叠回拨导致重复推送）
        connector.pages.add(page(0, 2, issue("PROJ-1", "登录页报错", T1), issue("PROJ-2", "导出失败", T2)));

        JiraSyncRunView result = service.doSync(1L);

        assertEquals(0, result.imported());
        assertEquals(2, requirementService.store.size()); // 无重复
    }

    @Test
    void DRAFT需求被Jira更新刷新() {
        connector.pages.add(page(0, 1, issue("PROJ-1", "旧标题", T1)));
        service.doSync(1L);
        connector.pages.add(page(0, 1, issue("PROJ-1", "新标题", T2)));

        JiraSyncRunView result = service.doSync(1L);

        assertEquals(0, result.imported());
        assertEquals(1, result.updated());
        assertEquals("新标题", requirementService.store.get("req-1").getTitle());
        // 更新时间随 issue 自身 updated 刷新（不是同步时刻）
        assertEquals(T2, requirementService.store.get("req-1").getUpdatedAt());
    }

    @Test
    void 进入流程的需求托管字段仍刷新本地字段不动() {
        connector.pages.add(page(0, 1, issue("PROJ-1", "旧标题", T1)));
        service.doSync(1L);
        RequirementEntity stored = requirementService.store.get("req-1");
        stored.setStatus(RequirementEntity.STATUS_ANALYZING);
        stored.setOwnerId("local-owner"); // 本地字段

        IntegrationConnector.JiraIssue changed = new IntegrationConnector.JiraIssue("PROJ-1", "新标题",
                "新描述", "Bug", "Highest", List.of(), "In Progress", T1, T2, "张三",
                "王五", null, List.of(), null, null);
        connector.pages.add(page(0, 1, changed));
        JiraSyncRunView result = service.doSync(1L);

        assertEquals(1, result.updated());
        assertEquals(0, result.skipped());
        // 托管字段刷新
        assertEquals("新标题", stored.getTitle());
        assertEquals("新描述", stored.getDescription());
        assertEquals("Highest", stored.getPriority());
        assertEquals("王五", stored.getAssignee());
        // 本地字段不动
        assertEquals(RequirementEntity.STATUS_ANALYZING, stored.getStatus());
        assertEquals("local-owner", stored.getOwnerId());
        assertEquals("In Progress", linkStore.get("PROJ-1").getStatus()); // 链接状态仍刷新
    }

    @Test
    void 连接器失败收敛错误发失败事件() {
        connector.failure = new DevMindException(ErrorCode.BAD_REQUEST, "拉取 Jira issue 失败：HTTP 401");

        JiraSyncRunView result = service.doSync(1L);

        assertNotNull(result.error());
        assertEquals(0, result.imported());
        assertNotNull(cfg.getLastError());
        assertEquals(List.of(false), integrationService.callOk);
        assertEquals(1, eventPublisher.events.size());
        assertEquals(Boolean.FALSE, ((SimpleDomainEvent) eventPublisher.events.get(0)).success());
    }

    @Test
    void 分页拉取直到取尽() {
        connector.pages.add(page(0, 3, issue("PROJ-1", "a", T1), issue("PROJ-2", "b", T1)));
        connector.pages.add(page(2, 3, issue("PROJ-3", "c", T2)));

        JiraSyncRunView result = service.doSync(1L);

        assertEquals(3, result.imported());
        assertEquals(2, result.pages());
    }

    @Test
    void JQL拼装只含project与附加片段() {
        assertEquals("project = PROJ ORDER BY created asc",
                JiraSyncService.buildJql("PROJ", null));
        assertEquals("project = PROJ ORDER BY created asc",
                JiraSyncService.buildJql("PROJ", "  "));
        assertEquals("project = PROJ AND (issuetype in (Story, Bug) AND labels = ai) ORDER BY created asc",
                JiraSyncService.buildJql("PROJ", "issuetype in (Story, Bug) AND labels = ai"));
    }

    @Test
    void 到期判定() {
        assertTrue(JiraSyncService.isDue(cfg, Instant.now())); // 从未跑过
        cfg.setLastSyncAt(Instant.now());
        assertEquals(false, JiraSyncService.isDue(cfg, Instant.now())); // 间隔内
        cfg.setLastSyncAt(Instant.now().minusSeconds(400));
        assertTrue(JiraSyncService.isDue(cfg, Instant.now())); // 超间隔（默认 300s）
    }

    @Test
    void 标题截断240字符() {
        connector.pages.add(page(0, 1, issue("PROJ-9", "长".repeat(300), T1)));
        service.doSync(1L);
        assertEquals(240, requirementService.store.get("req-1").getTitle().length());
    }
}
