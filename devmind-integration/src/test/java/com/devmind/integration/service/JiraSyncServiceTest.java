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
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
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
 * connector 喂队列页面。覆盖：首轮导入/幂等重放/DRAFT 刷新/进行中不覆盖/失败不推水印/分页。
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
            super(null, null, null, null, null, null);
        }

        @Override
        public synchronized RequirementView create(String projectId, RequirementRequest req) {
            String id = "req-" + (++seq);
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId(projectId);
            e.setTitle(req.title());
            e.setDescription(req.description());
            e.setStatus(RequirementEntity.STATUS_DRAFT);
            store.put(id, e);
            return toView(e);
        }

        @Override
        public RequirementView update(String projectId, String requirementId, RequirementRequest req) {
            RequirementEntity e = requireEntity(projectId, requirementId);
            if (req.title() != null) {
                e.setTitle(req.title());
            }
            if (req.description() != null) {
                e.setDescription(req.description());
            }
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
                    e.getDescription(), e.getStatus(), null, null, "test", Instant.now(), Instant.now());
        }
    }

    static class FakeIntegrationService extends IntegrationService {
        final IntegrationEntity integration;
        final List<Boolean> callOk = new ArrayList<>();

        FakeIntegrationService(IntegrationEntity integration) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, List.of());
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
                List.of("ai"), "Open", updated, updated, "张三");
    }

    private IssuePage page(int startAt, int total, IntegrationConnector.JiraIssue... issues) {
        return new IssuePage(startAt, issues.length, total, List.of(issues));
    }

    // ---------------- 用例 ----------------

    @Test
    void 首轮导入建需求登记链接推进水印() {
        connector.pages.add(page(0, 2, issue("PROJ-1", "登录页报错", T1), issue("PROJ-2", "导出失败", T2)));

        JiraSyncRunView result = service.doSync(1L);

        assertEquals(2, result.imported());
        assertEquals(0, result.updated());
        assertEquals(1, result.pages());
        assertNull(result.error());
        // 需求：DRAFT + [KEY] 前缀标题 + 来源尾注
        assertEquals(2, requirementService.store.size());
        RequirementEntity r1 = requirementService.store.get("req-1");
        assertEquals("[PROJ-1] 登录页报错", r1.getTitle());
        assertEquals(RequirementEntity.STATUS_DRAFT, r1.getStatus());
        assertTrue(r1.getDescription().contains("http://jira.local/browse/PROJ-1"));
        // 链接：REQUIREMENT ↔ ISSUE
        ExternalLinkEntity link = linkStore.get("PROJ-1");
        assertEquals(ExternalLinkEntity.INTERNAL_REQUIREMENT, link.getInternalType());
        assertEquals("req-1", link.getInternalId());
        assertEquals("Open", link.getStatus());
        // 水印 = max(updated) 回拨 60s；运行状态落库
        assertEquals(T2.minusSeconds(60), cfg.getLastWatermark());
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
        assertEquals("[PROJ-1] 新标题", requirementService.store.get("req-1").getTitle());
    }

    @Test
    void 进入流程的需求不再被覆盖仅刷链接状态() {
        connector.pages.add(page(0, 1, issue("PROJ-1", "旧标题", T1)));
        service.doSync(1L);
        requirementService.store.get("req-1").setStatus(RequirementEntity.STATUS_ANALYZING);

        IntegrationConnector.JiraIssue changed = new IntegrationConnector.JiraIssue("PROJ-1", "新标题",
                "新描述", "Bug", "High", List.of(), "In Progress", T1, T2, "张三");
        connector.pages.add(page(0, 1, changed));
        JiraSyncRunView result = service.doSync(1L);

        assertEquals(0, result.updated());
        assertEquals(1, result.skipped());
        assertEquals("[PROJ-1] 旧标题", requirementService.store.get("req-1").getTitle());
        assertEquals("In Progress", linkStore.get("PROJ-1").getStatus()); // 链接状态仍刷新
    }

    @Test
    void 连接器失败不推水印落错误发失败事件() {
        connector.failure = new DevMindException(ErrorCode.BAD_REQUEST, "拉取 Jira issue 失败：HTTP 401");

        JiraSyncRunView result = service.doSync(1L);

        assertNotNull(result.error());
        assertEquals(0, result.imported());
        assertNull(cfg.getLastWatermark()); // 水印不动，下轮重拉
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
        assertEquals(T2.minusSeconds(60), cfg.getLastWatermark());
    }

    @Test
    void JQL拼装覆盖首轮与增量() {
        assertEquals("project = PROJ ORDER BY created asc", JiraSyncService.buildJql(cfg));

        cfg.setJql("issuetype in (Story, Bug) AND labels = ai");
        assertEquals("project = PROJ AND (issuetype in (Story, Bug) AND labels = ai) ORDER BY created asc",
                JiraSyncService.buildJql(cfg));

        cfg.setLastWatermark(T1);
        String jql = JiraSyncService.buildJql(cfg);
        assertTrue(jql.startsWith("project = PROJ AND (issuetype in (Story, Bug) AND labels = ai) AND updated >= \""));
        assertTrue(jql.endsWith("ORDER BY updated asc"));
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
        IntegrationConnector.JiraIssue longSummary = issue("PROJ-9", "长".repeat(300), T1);
        String title = JiraSyncService.renderTitle(longSummary);
        assertEquals(240, title.length());
        assertTrue(title.startsWith("[PROJ-9] "));
    }
}
