package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.connector.IntegrationConnector.IssueTransition;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.JiraManagedFields;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JiraIssueActionService 回写闭环单测（无 Spring 上下文，fake 手法同 JiraSyncServiceTest）：
 * 非 Jira 来源 / 无链接 / 集成禁用 / transitionId 不在可用列表均拒绝；
 * 成功路径 = 执行转换 + link.status 刷新 + 托管字段刷新（本地 status 不动）+ 审计/事件。
 */
class JiraIssueActionServiceTest {

    private static final Instant T1 = Instant.parse("2026-08-28T01:00:00Z");

    private IntegrationEntity integration;
    private FakeConnector connector;
    private FakeRequirementService requirementService;
    private FakeIntegrationService integrationService;
    private FakeEventPublisher eventPublisher;
    private ExternalLinkRepository linkRepo;
    private ExternalLinkEntity link;
    private JiraIssueActionService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeConnector implements IntegrationConnector {
        List<IssueTransition> transitions = new ArrayList<>();
        IssuePage refreshPage = new IssuePage(0, 0, 0, List.of());
        final List<String> executed = new ArrayList<>();

        @Override public String type() { return IntegrationEntity.TYPE_JIRA; }
        @Override public TestResult testConnection(IntegrationEntity c, String t) { throw new UnsupportedOperationException(); }
        @Override public List<ExternalProject> listProjects(IntegrationEntity c, String t) { throw new UnsupportedOperationException(); }
        @Override public MergeRequestRef createMergeRequest(IntegrationEntity c, String t, MrSpec s) { throw new UnsupportedOperationException(); }
        @Override public ReleaseRef createRelease(IntegrationEntity c, String t, ReleaseSpec s) { throw new UnsupportedOperationException(); }

        @Override
        public List<IssueTransition> listTransitions(IntegrationEntity c, String token, String issueKey) {
            return transitions;
        }

        @Override
        public void transitionIssue(IntegrationEntity c, String token, String issueKey, String transitionId) {
            executed.add(issueKey + ":" + transitionId);
        }

        @Override
        public IssuePage searchIssues(IntegrationEntity c, String token, IssueQuery query) {
            return refreshPage;
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null);
        }

        RequirementEntity add(String id, String projectId, String source) {
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId(projectId);
            e.setSource(source);
            e.setStatus(RequirementEntity.STATUS_DRAFT);
            e.setTitle("原标题");
            store.put(id, e);
            return e;
        }

        @Override
        public RequirementEntity requireEntity(String projectId, String requirementId) {
            RequirementEntity e = store.get(requirementId);
            if (e == null || !projectId.equals(e.getProjectId())) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
            }
            return e;
        }

        @Override
        public com.devmind.project.dto.RequirementView syncFromJira(String projectId, String requirementId,
                                                                    JiraManagedFields f) {
            RequirementEntity e = requireEntity(projectId, requirementId);
            e.setTitle(f.title());
            return null;
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

    @BeforeEach
    void setUp() {
        integration = new IntegrationEntity();
        integration.setId(7L);
        integration.setName("公司 Jira");
        integration.setType(IntegrationEntity.TYPE_JIRA);
        integration.setBaseUrl("http://jira.local");
        integration.setStatus(IntegrationEntity.STATUS_ENABLED);

        requirementService = new FakeRequirementService();
        requirementService.add("req-jira", "p1", RequirementEntity.SOURCE_JIRA);
        requirementService.add("req-local", "p1", RequirementEntity.SOURCE_LOCAL);

        link = new ExternalLinkEntity();
        link.setId(1L);
        link.setProjectId("p1");
        link.setIntegrationId(7L);
        link.setInternalType(ExternalLinkEntity.INTERNAL_REQUIREMENT);
        link.setInternalId("req-jira");
        link.setExternalType(ExternalLinkEntity.EXTERNAL_ISSUE);
        link.setExternalKey("PROJ-1");
        link.setStatus("Open");

        linkRepo = proxy(ExternalLinkRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findByProjectIdAndInternalTypeAndInternalId" ->
                            "req-jira".equals(args[2]) ? List.of(link) : List.of();
                    case "save" -> args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        connector = new FakeConnector();
        connector.transitions = List.of(
                new IssueTransition("11", "开始处理", "In Progress"),
                new IssueTransition("21", "完成", "Done"));
        integrationService = new FakeIntegrationService(integration);
        eventPublisher = new FakeEventPublisher();

        service = new JiraIssueActionService(linkRepo, integrationService, requirementService,
                new IdentityService(null, null, null), new AuditService(null), eventPublisher,
                List.of(connector));
    }

    @Test
    void 列出当前可用转换() {
        List<JiraTransitionView> list = service.listTransitions("p1", "req-jira");
        assertEquals(2, list.size());
        assertEquals("21", list.get(1).id());
        assertEquals("完成", list.get(1).name());
        assertEquals("Done", list.get(1).toStatus());
    }

    @Test
    void 非Jira来源拒绝() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.listTransitions("p1", "req-local"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 无关联链接拒绝() {
        requirementService.add("req-nolink", "p1", RequirementEntity.SOURCE_JIRA);
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.listTransitions("p1", "req-nolink"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 集成禁用拒绝() {
        integration.setStatus(IntegrationEntity.STATUS_DISABLED);
        assertThrows(DevMindException.class, () -> service.listTransitions("p1", "req-jira"));
    }

    @Test
    void 不在可用列表的转换拒绝且不写远端() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.transit("p1", "req-jira", "999"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(connector.executed.isEmpty());
    }

    @Test
    void 空transitionId拒绝() {
        assertThrows(DevMindException.class, () -> service.transit("p1", "req-jira", " "));
    }

    @Test
    void 执行成功后刷新远端状态与托管字段本地status不动() {
        RequirementEntity stored = requirementService.store.get("req-jira");
        stored.setStatus(RequirementEntity.STATUS_IN_PROGRESS); // 本地流程中
        connector.refreshPage = new IssuePage(0, 1, 1, List.of(
                new JiraIssue("PROJ-1", "Jira 新标题", "描述", "Bug", "High", List.of(), "Done",
                        T1, T1, "张三", "李四", null, List.of())));

        JiraTransitionResultView result = service.transit("p1", "req-jira", "21");

        assertEquals(List.of("PROJ-1:21"), connector.executed);
        assertEquals("完成", result.transition().name());
        assertEquals("Done", result.remoteStatus());
        assertEquals("Done", link.getStatus());                    // 链接状态刷新
        assertEquals("Jira 新标题", stored.getTitle());            // 托管字段刷新
        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, stored.getStatus()); // 本地 status 不动
        assertEquals(List.of(true), integrationService.callOk);    // 调用审计
        assertEquals(1, eventPublisher.events.size());
        assertEquals("integration.jira.transitioned", eventPublisher.events.get(0).type());
    }

    @Test
    void 刷新失败不回滚转换() {
        connector.refreshPage = new IssuePage(0, 0, 0, List.of()); // 拉回空

        JiraTransitionResultView result = service.transit("p1", "req-jira", "11");

        assertEquals(List.of("PROJ-1:11"), connector.executed);
        assertEquals("Open", result.remoteStatus()); // 回退链接旧值
        assertEquals(1, eventPublisher.events.size()); // 事件仍发
        assertEquals(List.of(true), integrationService.callOk);
    }
}
