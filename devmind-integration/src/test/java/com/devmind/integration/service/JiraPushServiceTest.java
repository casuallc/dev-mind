package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.ExternalProject;
import com.devmind.integration.connector.IntegrationConnector.IssueRef;
import com.devmind.integration.connector.IntegrationConnector.IssueSpec;
import com.devmind.integration.connector.IntegrationConnector.IssueTypeRef;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.connector.IntegrationConnector.PriorityRef;
import com.devmind.integration.connector.IntegrationConnector.UserRef;
import com.devmind.integration.dto.JiraPushRequest;
import com.devmind.integration.dto.JiraPushResultView;
import com.devmind.integration.dto.JiraPushTargetsView;
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JiraPushService 推送闭环单测（无 Spring 上下文，fake 手法同 JiraIssueActionServiceTest）：
 * 幂等（需求级 + 跨需求同 key）/ 非 LOCAL / 集成禁用 / 参数校验 / createIssue 失败不落任何本地状态 /
 * 回读失败时 link 已登记且需求已转托管（安全态）/ 选项与默认值 / 手动刷新。
 */
class JiraPushServiceTest {

    private static final Instant T1 = Instant.parse("2026-09-01T01:00:00Z");
    private static final String BACKLINK = "http://localhost:5173/projects/p1/requirements/req-local";

    private IntegrationEntity integration;
    private FakeConnector connector;
    private FakeRequirementService requirementService;
    private FakeIntegrationService integrationService;
    private FakeEventPublisher eventPublisher;
    private JiraPushService service;

    private final Map<String, ExternalLinkEntity> linkStore = new HashMap<>();
    private long linkSeq = 0;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeConnector implements IntegrationConnector {
        final List<IssueSpec> created = new ArrayList<>();
        List<ExternalProject> projects = List.of();
        List<PriorityRef> priorities = List.of();
        List<IssueTypeRef> issueTypes = List.of();
        List<UserRef> assignableUsers = List.of();
        /** createIssue 的返回键；置 null 表示连接器直接抛错 */
        String createdKey = "PROJ-123";
        /** getIssue 的返回；置 null 表示回读抛错 */
        JiraIssue readback = null;
        boolean readbackFails = false;
        boolean listPrioritiesFails = false;

        @Override public String type() { return IntegrationEntity.TYPE_JIRA; }
        @Override public TestResult testConnection(IntegrationEntity c, String t) { throw new UnsupportedOperationException(); }
        @Override public MergeRequestRef createMergeRequest(IntegrationEntity c, String t, MrSpec s) { throw new UnsupportedOperationException(); }
        @Override public ReleaseRef createRelease(IntegrationEntity c, String t, ReleaseSpec s) { throw new UnsupportedOperationException(); }

        @Override
        public List<ExternalProject> listProjects(IntegrationEntity c, String token) {
            return projects;
        }

        @Override
        public IssueRef createIssue(IntegrationEntity c, String token, IssueSpec spec) {
            if (createdKey == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "创建 Jira issue 失败：HTTP 400 缺少必填字段");
            }
            created.add(spec);
            return new IssueRef("10201", createdKey, c.getBaseUrl() + "/browse/" + createdKey);
        }

        @Override
        public JiraIssue getIssue(IntegrationEntity c, String token, String issueKey, String fields) {
            if (readbackFails) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "读取 Jira issue 失败：HTTP 500 服务不可用");
            }
            return readback;
        }

        @Override
        public List<IssueTypeRef> listIssueTypes(IntegrationEntity c, String token, String projectKey) {
            return issueTypes;
        }

        @Override
        public List<PriorityRef> listPriorities(IntegrationEntity c, String token) {
            if (listPrioritiesFails) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "拉取 Jira 优先级失败：HTTP 503");
            }
            return priorities;
        }

        @Override
        public List<UserRef> listAssignableUsers(IntegrationEntity c, String token, String projectKey, String q) {
            return assignableUsers;
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();
        final List<JiraManagedFields> syncedFields = new ArrayList<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null);
        }

        RequirementEntity add(String id, String projectId, String source, long seq) {
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId(projectId);
            e.setSource(source);
            e.setSeq(seq);
            e.setStatus(RequirementEntity.STATUS_DRAFT);
            e.setTitle("原标题");
            e.setDescription("原始描述");
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

        /** 真实实现只改这两列（+updatedAt），见 CAP-47 FR-04 */
        @Override
        public void markPushedToJira(String projectId, String requirementId, String externalKey) {
            RequirementEntity e = requireEntity(projectId, requirementId);
            e.setSource(RequirementEntity.SOURCE_JIRA);
            e.setExternalKey(externalKey);
        }

        @Override
        public RequirementView syncFromJira(String projectId, String requirementId, JiraManagedFields f) {
            RequirementEntity e = requireEntity(projectId, requirementId);
            syncedFields.add(f);
            e.setSource(RequirementEntity.SOURCE_JIRA);
            e.setTitle(f.title());
            return null;
        }
    }

    static class FakeIntegrationService extends IntegrationService {
        final IntegrationEntity integration;
        final List<String> calls = new ArrayList<>();
        boolean personalBound = false;
        boolean botConfigured = true;

        FakeIntegrationService(IntegrationEntity integration) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, List.of());
            this.integration = integration;
        }

        @Override public String tokenOf(IntegrationEntity e) {
            if (!botConfigured) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "实例「" + e.getName() + "」未配置平台凭证（自动化路径需要）");
            }
            return "bot-token";
        }

        /** CAP-35 身份链：个人账号 → 机器人凭证 → 报错引导绑定 */
        @Override public WriteIdentity resolveWriteIdentity(String actor, IntegrationEntity e) {
            if (personalBound) {
                return new WriteIdentity("personal-token", IdentitySource.PERSONAL);
            }
            if (!botConfigured) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "未配置可用凭据：请先在「我的 → 第三方账号」绑定该平台账号，或联系 ADMIN 为实例配置平台凭证");
            }
            return new WriteIdentity("bot-token", IdentitySource.BOT);
        }

        @Override
        public void recordCall(Long id, String action, String it, String ii, boolean ok, String err) {
            calls.add(action + ":" + ok);
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
        requirementService.add("req-local", "p1", RequirementEntity.SOURCE_LOCAL, 1);
        requirementService.add("req-jira", "p1", RequirementEntity.SOURCE_JIRA, 2);

        connector = new FakeConnector();
        connector.priorities = List.of(new PriorityRef("2", "High"));
        connector.readback = new JiraIssue("PROJ-123", "支持导出对账单", "描述", "需求", "High",
                List.of("ai"), "待处理", T1, T1, "张三", "李四",
                LocalDate.parse("2026-10-31"), List.of(), null, null);

        integrationService = new FakeIntegrationService(integration);
        eventPublisher = new FakeEventPublisher();

        ExternalLinkRepository linkRepo = proxy(ExternalLinkRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findFirstByProjectIdAndInternalTypeAndInternalIdAndExternalTypeOrderByIdDesc" ->
                    linkStore.values().stream()
                            .filter(l -> args[0].equals(l.getProjectId()))
                            .filter(l -> args[2].equals(l.getInternalId()))
                            .filter(l -> args[3].equals(l.getExternalType()))
                            .findFirst();
            case "findFirstByIntegrationIdAndExternalTypeAndExternalKeyOrderByIdDesc" ->
                    Optional.ofNullable(linkStore.get((String) args[2]));
            case "save" -> {
                ExternalLinkEntity link = (ExternalLinkEntity) args[0];
                if (link.getId() == null) {
                    link.setId(++linkSeq);
                }
                linkStore.put(link.getExternalKey(), link);
                yield link;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });

        IntegrationRepository integrationRepo = proxy(IntegrationRepository.class, (p, m, args) ->
                "findByTypeAndStatus".equals(m.getName()) ? List.of(integration) : null);

        JiraSyncConfigRepository configRepo = proxy(JiraSyncConfigRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findByProjectIdOrderByCreatedAtDesc" -> configs;
            case "findByIntegrationIdAndProjectId" -> configs.stream()
                    .filter(c -> c.getIntegrationId().equals(args[0]) && c.getProjectId().equals(args[1]))
                    .findFirst();
            default -> throw new UnsupportedOperationException(m.getName());
        });

        service = new JiraPushService(integrationRepo, configRepo, linkRepo, integrationService,
                requirementService, new IdentityService(null, null, null), new AuditService(null),
                eventPublisher, List.of(connector), new JiraWriteGuard());
    }

    /** 每个用例独立可改的同步配置（默认空 = 未被同步覆盖） */
    private final List<JiraSyncConfigEntity> configs = new ArrayList<>();

    private JiraSyncConfigEntity syncConfig(String projectId, Long integrationId, String key, boolean enabled) {
        JiraSyncConfigEntity cfg = new JiraSyncConfigEntity();
        cfg.setProjectId(projectId);
        cfg.setIntegrationId(integrationId);
        cfg.setJiraProjectKey(key);
        cfg.setEnabled(enabled);
        configs.add(cfg);
        return cfg;
    }

    private ExternalLinkEntity link(String projectId, String internalId, String key) {
        ExternalLinkEntity link = new ExternalLinkEntity();
        link.setId(++linkSeq);
        link.setProjectId(projectId);
        link.setIntegrationId(7L);
        link.setInternalType(ExternalLinkEntity.INTERNAL_REQUIREMENT);
        link.setInternalId(internalId);
        link.setExternalType(ExternalLinkEntity.EXTERNAL_ISSUE);
        link.setExternalKey(key);
        linkStore.put(key, link);
        return link;
    }

    private static JiraPushRequest request(String title) {
        return new JiraPushRequest(7L, "PROJ", "10001", title, "原始描述", BACKLINK,
                "High", "lisi", List.of("ai", "账单"), "2026-10-31");
    }

    // ---------------- FR-03 推送 ----------------

    @Test
    void 推送成功建issue登记link并转托管() {
        JiraPushResultView result = service.push("p1", "req-local", request("支持导出对账单"));

        assertEquals(1, connector.created.size());
        IssueSpec spec = connector.created.get(0);
        assertEquals("PROJ", spec.projectKey());          // 项目 key 大小写归一
        assertEquals("10001", spec.issueTypeId());
        assertEquals("支持导出对账单", spec.summary());
        assertEquals("High", spec.priorityName());
        assertEquals("lisi", spec.assigneeName());
        assertEquals(List.of("ai", "账单"), spec.labels());
        assertEquals(LocalDate.parse("2026-10-31"), spec.dueDate());
        // 描述尾部由服务端强制追加平台回链
        assertTrue(spec.description().startsWith("原始描述\n\n"));
        assertTrue(spec.description().endsWith("REQ-1 · " + BACKLINK));

        assertEquals("PROJ-123", result.externalKey());
        assertEquals("http://jira.local/browse/PROJ-123", result.externalUrl());
        assertEquals("待处理", result.remoteStatus());
        assertEquals("需求", result.issueType());
        assertFalse(result.syncCovered());   // 无同步配置 → 托管字段不会自动刷新

        ExternalLinkEntity link = linkStore.get("PROJ-123");
        assertNotNull(link);
        assertEquals("req-local", link.getInternalId());
        assertEquals("http://jira.local/browse/PROJ-123", link.getExternalUrl());
        assertEquals("待处理", link.getStatus());

        RequirementEntity stored = requirementService.store.get("req-local");
        assertEquals(RequirementEntity.SOURCE_JIRA, stored.getSource());
        assertEquals("PROJ-123", stored.getExternalKey());
        assertEquals("原标题", stored.getTitle());     // FR-04：托管字段不被套用

        assertEquals(List.of("create_issue:true"), integrationService.calls);
        assertEquals("integration.jira.pushed", eventPublisher.events.get(0).type());
        assertTrue(((SimpleDomainEvent) eventPublisher.events.get(0)).success());
    }

    @Test
    void 有同步配置时标记为已覆盖() {
        syncConfig("p1", 7L, "PROJ", true);

        assertTrue(service.push("p1", "req-local", request("标题")).syncCovered());
    }

    @Test
    void 需求级幂等重复推送409且不建issue() {
        link("p1", "req-local", "PROJ-123");
        requirementService.store.get("req-local").setExternalKey("PROJ-123");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("PROJ-123"));
        assertTrue(connector.created.isEmpty());
        assertTrue(integrationService.calls.isEmpty());
    }

    @Test
    void 仅有externalKey无link也判幂等() {
        requirementService.store.get("req-local").setExternalKey("PROJ-9");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("PROJ-9"));
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 已转托管且已关联时优先报409带既有key() {
        // 推送成功后 source=JIRA 与 link 同时成立：重复推送必须回既有 key（409），
        // 而不是「已是 JIRA 来源」（没有 key 可追查）——幂等检查先于来源守卫
        link("p1", "req-local", "PROJ-123");
        requirementService.store.get("req-local").setSource(RequirementEntity.SOURCE_JIRA);
        requirementService.store.get("req-local").setExternalKey("PROJ-123");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("PROJ-123"));
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 非自建需求拒绝推送() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-jira", request("标题")));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 集成禁用拒绝推送() {
        integration.setStatus(IntegrationEntity.STATUS_DISABLED);

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 无可用凭据时400引导绑定() {
        integrationService.botConfigured = false;

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("第三方账号"));
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 个人账号绑定后按个人身份推送() {
        integrationService.personalBound = true;

        service.push("p1", "req-local", request("标题"));

        assertEquals(List.of("create_issue:true"), integrationService.calls);
        assertTrue(((SimpleDomainEvent) eventPublisher.events.get(0)).summary()
                .contains("已推送到 Jira PROJ-123"));
    }

    @Test
    void createIssue失败不落任何本地状态() {
        connector.createdKey = null;

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(linkStore.isEmpty());
        RequirementEntity stored = requirementService.store.get("req-local");
        assertEquals(RequirementEntity.SOURCE_LOCAL, stored.getSource());
        assertNull(stored.getExternalKey());
        assertEquals(List.of("create_issue:false"), integrationService.calls);
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    void 回读失败时link已登记且需求仍转托管() {
        connector.readbackFails = true;

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.INTERNAL, e.getErrorCode());
        assertTrue(e.getMessage().contains("PROJ-123"));   // message 内嵌 key，引导手动刷新
        assertTrue(e.getMessage().contains("从 Jira 刷新"));
        ExternalLinkEntity link = linkStore.get("PROJ-123");
        assertNotNull(link);                              // 安全态：link 不丢
        assertNull(link.getStatus());                     // 回读失败 → status 留空
        RequirementEntity stored = requirementService.store.get("req-local");
        assertEquals(RequirementEntity.SOURCE_JIRA, stored.getSource());
        assertEquals("PROJ-123", stored.getExternalKey());
        assertEquals("原标题", stored.getTitle());        // FR-04：托管字段未被套用
        assertEquals(List.of("create_issue:false"), integrationService.calls);
    }

    @Test
    void 同一key已属别的需求时409不登记() {
        link("p1", "req-other", "PROJ-123");

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("标题")));

        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("req-other"));
        assertEquals("req-other", linkStore.get("PROJ-123").getInternalId()); // 既有 link 未被改写
        assertNull(requirementService.store.get("req-local").getExternalKey());
        assertEquals(List.of("create_issue:false"), integrationService.calls);
    }

    @Test
    void 参数校验拒绝非法入参() {
        // 标题超长
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", request("x".repeat(256)))).getErrorCode());
        // 标题为空
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local", request(" ")));
        // 优先级不在词表内
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(DevMindException.class,
                () -> service.push("p1", "req-local", new JiraPushRequest(7L, "PROJ", "10001", "标题",
                        "描述", BACKLINK, "Urgent", null, List.of(), null))).getErrorCode());
        // 标签含空格 / 逗号
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of("a b"), null)));
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of("a,b"), null)));
        // 截止日期非法
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of(), "2026/10/31")));
        // 回链缺失
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", " ", null, null, List.of(), null)));
        // 任务类型缺失
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", null, "标题", "描述", BACKLINK, null, null, List.of(), null)));

        assertTrue(connector.created.isEmpty());
        assertTrue(linkStore.isEmpty());
    }

    @Test
    void 优先级词表拉取失败时跳过校验() {
        connector.priorities = List.of();
        connector.listPrioritiesFails = true;

        // 词表不可用不该阻断写操作（读接口抖动）
        service.push("p1", "req-local", new JiraPushRequest(7L, "PROJ", "10001", "标题",
                "描述", BACKLINK, "任意值", null, List.of(), null));

        assertEquals(1, connector.created.size());
    }

    @Test
    void 回链文案组装() {
        assertEquals("REQ-1 · " + BACKLINK, JiraPushService.composeDescription(null, "REQ-1", BACKLINK));
        assertEquals("REQ-1 · " + BACKLINK, JiraPushService.composeDescription("  ", "REQ-1", BACKLINK));
        assertEquals("正文\n\nREQ-1 · " + BACKLINK,
                JiraPushService.composeDescription(" 正文 ", "REQ-1", BACKLINK));
    }

    @Test
    void issueKey前缀即Jira项目key() {
        assertEquals("PROJ", JiraPushService.projectKeyOf("PROJ-123"));
        assertEquals("MY-PROJ", JiraPushService.projectKeyOf("MY-PROJ-1"));
        assertNull(JiraPushService.projectKeyOf("NOID"));
        assertNull(JiraPushService.projectKeyOf(null));
    }

    // ---------------- FR-02 候选项 ----------------

    @Test
    void 候选项给齐默认值与身份来源() {
        syncConfig("p1", 7L, "PROJ", true);
        connector.projects = List.of(new ExternalProject("PROJ", "示例项目", "http://jira.local", "main"));
        connector.issueTypes = List.of(new IssueTypeRef("10001", "需求", false),
                new IssueTypeRef("10004", "子任务", true));
        requirementService.store.get("req-local").setPriority("High");
        requirementService.store.get("req-local").setLabels("ai,账单");
        requirementService.store.get("req-local").setDueDate(LocalDate.parse("2026-10-31"));

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals(1, view.instances().size());
        assertEquals(7L, view.defaultIntegrationId());
        assertEquals("PROJ", view.defaultJiraProjectKey());
        assertNull(view.optionsError());
        assertEquals("BOT", view.identitySource());
        assertTrue(view.syncCovered());
        // 子任务不能作为顶层 issue 创建 → 过滤
        assertEquals(1, view.issueTypes().size());
        assertEquals("10001", view.issueTypes().get(0).id());
        assertEquals("PROJ 示例项目", view.jiraProjects().get(0).name());
        assertEquals("High", view.priorities().get(0).name());
        // 默认值取需求当前值
        assertEquals("原标题", view.defaults().title());
        assertEquals("原始描述", view.defaults().description());
        assertEquals("High", view.defaults().priority());
        assertEquals(List.of("ai", "账单"), view.defaults().labels());
        assertEquals("2026-10-31", view.defaults().dueDate());
    }

    @Test
    void 平台优先级未命中实例词表时不回填() {
        // 平台优先级是固定英文枚举，Jira 词表随实例语言包（中文实例返回「高/中/低」），两套只是偶尔重合。
        // 照填会被 validatePriority 拦成 400「优先级不在实例词表内」→ 不命中就不回填，留空让用户从词表选
        connector.priorities = List.of(new PriorityRef("2", "高"), new PriorityRef("3", "中"));
        requirementService.store.get("req-local").setPriority("High");

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals(2, view.priorities().size());
        assertEquals("高", view.priorities().get(0).name());
        assertNull(view.defaults().priority());
    }

    @Test
    void 词表拉不到时不回填优先级() {
        // 拉不到词表 = 无从判断是否同域，不赌（push 侧对用户手填值跳过校验，自动回填不无据推测）；
        // 选项拉取失败仍降级为空表 + optionsError，弹窗照常打开
        connector.priorities = List.of();
        connector.listPrioritiesFails = true;
        requirementService.store.get("req-local").setPriority("High");

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertNull(view.defaults().priority());
        assertNotNull(view.optionsError());
    }

    @Test
    void 无可用凭据时候选项降级但弹窗可打开() {
        integrationService.botConfigured = false;

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals("NONE", view.identitySource());
        assertNotNull(view.optionsError());
        assertTrue(view.optionsError().contains("第三方账号"));
        assertTrue(view.issueTypes().isEmpty());
        assertEquals(1, view.instances().size());   // 实例来自本地库，弹窗仍能渲染
    }

    @Test
    void 同步配置禁用时不判为已覆盖() {
        syncConfig("p1", 7L, "PROJ", false);

        assertFalse(service.targets("p1", "req-local").syncCovered());
    }

    @Test
    void 无同步配置时默认取候选实例首条() {
        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals(7L, view.defaultIntegrationId());
        assertNull(view.defaultJiraProjectKey());
        assertFalse(view.syncCovered());
    }

    // ---------------- FR-05 手动刷新 ----------------

    @Test
    void 手动刷新拉回托管字段() {
        link("p1", "req-local", "PROJ-123");
        requirementService.store.get("req-local").setSource(RequirementEntity.SOURCE_JIRA);
        connector.readback = new JiraIssue("PROJ-123", "Jira 新标题", "新描述", "任务", "Medium",
                List.of(), "In Progress", T1, T1, "张三", "王五", null, List.of("1.0"), 3600L, 1800L);

        JiraPushResultView result = service.refresh("p1", "req-local");

        assertEquals("PROJ-123", result.externalKey());
        assertEquals("In Progress", result.remoteStatus());
        assertEquals("任务", result.issueType());
        assertEquals("In Progress", linkStore.get("PROJ-123").getStatus());
        assertEquals(1, requirementService.syncedFields.size());
        assertEquals("Jira 新标题", requirementService.store.get("req-local").getTitle());
        assertEquals(List.of("jira_refresh:true"), integrationService.calls);
    }

    @Test
    void 无关联链接时刷新拒绝() {
        DevMindException e = assertThrows(DevMindException.class, () -> service.refresh("p1", "req-local"));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(integrationService.calls.isEmpty());
    }

    @Test
    void 刷新失败上报调用且不改链接状态() {
        link("p1", "req-local", "PROJ-123").setStatus("Open");
        connector.readbackFails = true;

        assertThrows(DevMindException.class, () -> service.refresh("p1", "req-local"));

        assertEquals("Open", linkStore.get("PROJ-123").getStatus());
        assertEquals(List.of("jira_refresh:false"), integrationService.calls);
    }
}
