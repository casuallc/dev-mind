package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.CreateFieldRef;
import com.devmind.integration.connector.IntegrationConnector.ExternalProject;
import com.devmind.integration.connector.IntegrationConnector.FieldOption;
import com.devmind.integration.connector.IntegrationConnector.IssueRef;
import com.devmind.integration.connector.IntegrationConnector.IssueSpec;
import com.devmind.integration.connector.IntegrationConnector.IssueTypeRef;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.connector.IntegrationConnector.PriorityRef;
import com.devmind.integration.connector.IntegrationConnector.UserRef;
import com.devmind.integration.dto.JiraAssignableUserView;
import com.devmind.integration.dto.JiraCreateFieldView;
import com.devmind.integration.dto.JiraCreateFieldsView;
import com.devmind.integration.dto.JiraOptionView;
import com.devmind.integration.dto.JiraPushOptionsView;
import com.devmind.integration.dto.JiraPushRequest;
import com.devmind.integration.dto.JiraPushResultView;
import com.devmind.integration.dto.JiraPushTargetsView;
import com.devmind.integration.dto.JiraPushTemplateRequest;
import com.devmind.integration.dto.JiraPushTemplateView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.JiraPushTemplateEntity;
import com.devmind.integration.model.JiraSyncConfigEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.JiraPushTemplateRepository;
import com.devmind.integration.repo.JiraSyncConfigRepository;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.JiraManagedFields;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

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
    private final Map<Long, JiraPushTemplateEntity> templateStore = new HashMap<>();
    private long templateSeq = 0;
    private final Map<String, UserEntity> users = new HashMap<>();
    /** 启用中的 JIRA 实例（默认只有 integration；跨实例用例往里加第二条） */
    private final List<IntegrationEntity> instances = new ArrayList<>();

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
        List<CreateFieldRef> createFields = List.of();
        /** createIssue 的返回键；置 null 表示连接器直接抛错 */
        String createdKey = "PROJ-123";
        /** getIssue 的返回；置 null 表示回读抛错 */
        JiraIssue readback = null;
        boolean readbackFails = false;
        boolean listPrioritiesFails = false;
        boolean listCreateFieldsFails = false;

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

        @Override
        public List<CreateFieldRef> listCreateFields(IntegrationEntity c, String token,
                                                     String projectKey, String issueTypeId) {
            if (listCreateFieldsFails) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "拉取 Jira 创建字段失败：HTTP 500");
            }
            return createFields;
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();
        final List<JiraManagedFields> syncedFields = new ArrayList<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null);
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
                        "未配置可用凭据：请先在「设置 → 第三方账号」绑定该平台账号，或联系 ADMIN 为实例配置平台凭证");
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
        instances.add(integration);

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
                switch (m.getName()) {
                    // 可变列表：跨实例默认值的用例要往里加第二个实例
                    case "findByTypeAndStatus" -> instances;
                    // toDefaultsView 要拿实例名展示
                    case "findById" -> instances.stream()
                            .filter(i -> i.getId().equals(args[0])).findFirst();
                    default -> null;
                });

        JiraSyncConfigRepository configRepo = proxy(JiraSyncConfigRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findByProjectIdOrderByCreatedAtDesc" -> configs;
            case "findByIntegrationIdAndProjectId" -> configs.stream()
                    .filter(c -> c.getIntegrationId().equals(args[0]) && c.getProjectId().equals(args[1]))
                    .findFirst();
            default -> throw new UnsupportedOperationException(m.getName());
        });

        JiraPushTemplateRepository templateRepo = proxy(JiraPushTemplateRepository.class,
                (p, m, args) -> switch (m.getName()) {
                    case "findByUserIdOrderByUpdatedAtDesc" -> templateStore.values().stream()
                            .filter(t -> args[0].equals(t.getUserId()))
                            .toList();
                    case "findByUserIdAndIntegrationIdAndJiraProjectKeyAndIssueTypeId" ->
                            templateStore.values().stream()
                                    .filter(t -> args[0].equals(t.getUserId())
                                            && t.getIntegrationId().equals(args[1])
                                            && t.getJiraProjectKey().equals(args[2])
                                            && t.getIssueTypeId().equals(args[3]))
                                    .findFirst();
                    case "findById" -> Optional.ofNullable(templateStore.get((Long) args[0]));
                    case "save" -> {
                        JiraPushTemplateEntity e = (JiraPushTemplateEntity) args[0];
                        if (e.getId() == null) {
                            e.setId(++templateSeq);
                        }
                        templateStore.put(e.getId(), e);
                        yield e;
                    }
                    case "delete" -> {
                        templateStore.remove(((JiraPushTemplateEntity) args[0]).getId());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        UserRepository userRepo = proxy(UserRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findByUsername" -> Optional.ofNullable(users.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        service = new JiraPushService(integrationRepo, configRepo, templateRepo, linkRepo, integrationService,
                requirementService, new IdentityService(userRepo, new BCryptPasswordEncoder(),
                new AuthProperties()),
                new AuditService(null), eventPublisher, List.of(connector), new JiraWriteGuard(),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserEntity addUser(String id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        users.put(username, u);
        return u;
    }

    /** 模拟登录（IdentityService.currentActor 从 SecurityContext 取真实用户） */
    private void loginAs(String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal(username, "DEVELOPER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
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
                "High", "lisi", List.of("ai", "账单"), "2026-10-31", null);
    }

    /** FR-08：带动态字段的推送入参（其余参数同 {@link #request}） */
    private static JiraPushRequest pushRequest(Map<String, Object> extraFields) {
        return new JiraPushRequest(7L, "PROJ", "10001", "支持导出对账单", "原始描述", BACKLINK,
                null, null, List.of(), null, extraFields);
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
                        "描述", BACKLINK, "Urgent", null, List.of(), null, null))).getErrorCode());
        // 标签含空格 / 逗号
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of("a b"), null, null)));
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of("a,b"), null, null)));
        // 截止日期非法
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK, null, null,
                        List.of(), "2026/10/31", null)));
        // 回链缺失
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", " ", null, null, List.of(), null, null)));
        // 任务类型缺失
        assertThrows(DevMindException.class, () -> service.push("p1", "req-local",
                new JiraPushRequest(7L, "PROJ", null, "标题", "描述", BACKLINK, null, null, List.of(), null, null)));

        assertTrue(connector.created.isEmpty());
        assertTrue(linkStore.isEmpty());
    }

    @Test
    void 优先级词表拉取失败时跳过校验() {
        connector.priorities = List.of();
        connector.listPrioritiesFails = true;

        // 词表不可用不该阻断写操作（读接口抖动）
        service.push("p1", "req-local", new JiraPushRequest(7L, "PROJ", "10001", "标题",
                "描述", BACKLINK, "任意值", null, List.of(), null, null));

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

    // ---------------- FR-08 动态必填字段 ----------------

    /** 用户真实撞到的那组：模块/影响版本/修复版本/到期日/时间跟踪 + 一个下拉自定义字段 */
    private void requiredFieldsLikeRealJira() {
        connector.createFields = List.of(
                new CreateFieldRef("summary", "摘要", true, "string", null, List.of(), false),
                new CreateFieldRef("components", "模块", true, "array", "component",
                        List.of(new FieldOption("10000", "后端"), new FieldOption("10001", "前端")), false),
                new CreateFieldRef("versions", "影响版本", true, "array", "version",
                        List.of(new FieldOption("10100", "1.0")), false),
                new CreateFieldRef("fixVersions", "修复的版本", true, "array", "version",
                        List.of(new FieldOption("10100", "1.0"), new FieldOption("10101", "2.0")), false),
                new CreateFieldRef("duedate", "到期日", true, "date", null, List.of(), false),
                new CreateFieldRef("timetracking", "时间跟踪", true, "timetracking", null, List.of(), false),
                new CreateFieldRef("customfield_10207", "缺陷类型", true, "option", null,
                        List.of(new FieldOption("10201", "功能缺陷")), false),
                // 有默认值的必填字段：Jira 自填，不该让用户填
                new CreateFieldRef("customfield_10606", "缺陷引入的活动", true, "option", null,
                        List.of(new FieldOption("10601", "需求分析")), true),
                // 非必填字段不进清单
                new CreateFieldRef("priority", "优先级", false, "option", null,
                        List.of(new FieldOption("3", "中")), true));
    }

    @Test
    void 必填字段按可渲染性分区且固定字段不重复渲染() {
        requiredFieldsLikeRealJira();

        JiraCreateFieldsView view = service.createFields("p1", "req-local", 7L, "PROJ", "10003");

        // duedate 固定表单已有（加必填校验即可），summary 本就必填——都不重复渲染
        assertEquals(List.of("duedate"), view.requiredFixed());
        assertEquals(List.of("components", "versions", "fixVersions", "timetracking", "customfield_10207"),
                view.fields().stream().map(JiraCreateFieldView::id).toList());
        assertTrue(view.unsupported().isEmpty());
        assertNull(view.error());
        // 组件/版本带候选值，时间跟踪是专用控件
        assertEquals(List.of("后端", "前端"),
                view.fields().get(0).options().stream().map(JiraOptionView::name).toList());
        assertEquals(JiraCreateFieldView.CONTROL_TIMETRACKING, view.fields().get(3).control());
        // 有默认值的必填字段与非常填字段都不出现
        assertTrue(view.fields().stream().noneMatch(f -> "customfield_10606".equals(f.id())));
        assertTrue(view.fields().stream().noneMatch(f -> "priority".equals(f.id())));
    }

    @Test
    void 渲染不了的必填字段进unsupported而不是硬塞文本框() {
        connector.createFields = List.of(
                // 级联选择：option 类型但无候选值 → 塞成自由文本只会误导用户
                new CreateFieldRef("customfield_10700", "归属组织", true, "option", null, List.of(), false),
                new CreateFieldRef("components", "模块", true, "array", "component",
                        List.of(new FieldOption("10000", "后端")), false));

        JiraCreateFieldsView view = service.createFields("p1", "req-local", 7L, "PROJ", "10003");

        assertEquals(List.of("customfield_10700"),
                view.unsupported().stream().map(JiraCreateFieldView::id).toList());
        assertTrue(view.unsupported().stream().allMatch(f -> f.control() == null));
        assertEquals(List.of("components"), view.fields().stream().map(JiraCreateFieldView::id).toList());
    }

    @Test
    void reserved字段project和issuetype和reporter不进unsupported列表() {
        // Jira createmeta 返回 project/issuetype/reporter 为必填，但平台已管理前两者，
        // Jira REST API 自动回填 reporter——三者都应静默跳过，不进 unsupported 以免阻断提交
        connector.createFields = List.of(
                new CreateFieldRef("project", "项目", true, "project", null,
                        List.of(new FieldOption("10311", "分布式消息")), false),
                new CreateFieldRef("issuetype", "问题类型", true, "issuetype", null,
                        List.of(new FieldOption("10103", "缺陷")), false),
                new CreateFieldRef("reporter", "报告人", true, "user", null, List.of(), false),
                new CreateFieldRef("components", "模块", true, "array", "component",
                        List.of(new FieldOption("10000", "后端")), false));

        JiraCreateFieldsView view = service.createFields("p1", "req-local", 7L, "PROJ", "10003");

        assertTrue(view.unsupported().isEmpty());
        assertEquals(List.of("components"), view.fields().stream().map(JiraCreateFieldView::id).toList());
    }

    @Test
    void 元数据拉不到时降级为空表且不抛错() {
        connector.listCreateFieldsFails = true;

        JiraCreateFieldsView view = service.createFields("p1", "req-local", 7L, "PROJ", "10003");

        // 读接口不可用不该把原本能推的类型也堵死：只给错误原文，由前端提示但不禁用提交
        assertTrue(view.fields().isEmpty());
        assertTrue(view.unsupported().isEmpty());
        assertNotNull(view.error());
    }

    @Test
    void 修复版本命中实例候选值才预填() {
        requiredFieldsLikeRealJira();
        requirementService.store.get("req-local").setFixVersions("2.0,不存在的版本");

        JiraCreateFieldsView view = service.createFields("p1", "req-local", 7L, "PROJ", "10003");

        // 只回填命中项（2.0 → id 10101）；不命中不猜（猜错版本比留空更糟）
        assertEquals(Map.of("fixVersions", List.of("10101")), view.prefill());
    }

    @Test
    void 动态字段随payload写入() {
        // 服务端不重解释取值（Jira 形态由前端按控件类型组装），标量/组件/时间跟踪原样过去
        JiraPushRequest req = pushRequest(Map.of("components", List.of(Map.of("id", "10000")),
                "timetracking", Map.of("originalEstimate", "2h"),
                "customfield_10800", 3));

        service.push("p1", "req-local", req);

        IssueSpec spec = connector.created.get(0);
        assertEquals(Map.of("components", List.of(Map.of("id", "10000")),
                "timetracking", Map.of("originalEstimate", "2h"),
                "customfield_10800", 3), spec.extraFields());
    }

    @Test
    void 动态字段不许覆盖描述等平台管理的字段() {
        // description 被覆盖 = 服务端强制追加的回链形同虚设；project/issuetype 被覆盖会推错项目
        for (String reserved : List.of("description", "project", "issuetype", "summary", "duedate")) {
            DevMindException e = assertThrows(DevMindException.class,
                    () -> service.push("p1", "req-local", pushRequest(Map.of(reserved, "x"))));
            assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        }
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 动态字段取值形态超两层直接拒绝() {
        // 放开等于把任意 JSON 转手发给 Jira，脏 payload 的错误反而更难读
        for (Object bad : List.of(List.of(List.of("a")), List.of(Map.of("a", List.of("b"))),
                Map.of("a", List.of("b")), Map.of("a", Map.of("b", "c")), new Object())) {
            assertThrows(DevMindException.class,
                    () -> service.push("p1", "req-local", pushRequest(Map.of("components", bad))));
        }
        assertTrue(connector.created.isEmpty());
    }

    @Test
    void 动态字段空值不写进payload() {
        service.push("p1", "req-local", pushRequest(Map.of("components", List.of(), "versions", "  ")));

        assertTrue(connector.created.get(0).extraFields().isEmpty());
    }

    // ---------------- 创建界面上没有的字段一律不写（用户撞到的 labels 400） ----------------
    //
    // 用户实报：HTTP 400 labels: Field 'labels' cannot be set. It is not on the appropriate
    // screen, or unknown.——平台固定表单永远带 labels/priority，但某个项目的创建界面上可能
    // 根本没有这些字段，于是推一次 400 一次。createmeta 的原始清单就是「这个界面上有什么」。

    @Test
    void 创建界面上没有的固定字段不写进payload() {
        // 该项目的创建界面只有标题/描述/经办人；优先级与标签都不在上面
        connector.createFields = List.of(
                new CreateFieldRef("summary", "摘要", true, "string", null, List.of(), false),
                new CreateFieldRef("description", "描述", true, "string", null, List.of(), false),
                new CreateFieldRef("assignee", "经办人", true, "user", null, List.of(), false));

        service.push("p1", "req-local", request("支持导出对账单"));

        IssueSpec spec = connector.created.get(0);
        assertEquals(List.of(), spec.labels());     // 需求本身有标签，但不写
        assertNull(spec.priorityName());            // 需求优先级 High 也不写
        assertEquals("lisi", spec.assigneeName());  // 界面上有的照写
        assertNotNull(spec.description());
    }

    @Test
    void 优先级不在创建界面上时不校验词表() {
        // 校验了也没用——反正不写；校验不过反而把本来能推的堵死
        connector.createFields = List.of(
                new CreateFieldRef("summary", "摘要", true, "string", null, List.of(), false));

        service.push("p1", "req-local", new JiraPushRequest(7L, "PROJ", "10001", "标题", "描述", BACKLINK,
                "Urgent", null, List.of(), null, null));   // Urgent 不在词表（只有 High）

        assertEquals(1, connector.created.size());
        assertNull(connector.created.get(0).priorityName());
    }

    @Test
    void 创建界面上没有的动态字段丢弃而不是发出去() {
        connector.createFields = List.of(
                new CreateFieldRef("summary", "摘要", true, "string", null, List.of(), false),
                new CreateFieldRef("components", "模块", false, "array", "component", List.of(), false));

        // 换过项目/类型后旧默认值可能指向已不在界面上的字段
        service.push("p1", "req-local", pushRequest(Map.of(
                "components", List.of(Map.of("id", "10000")),
                "customfield_99999", "陈旧取值")));

        assertEquals(Map.of("components", List.of(Map.of("id", "10000"))),
                connector.created.get(0).extraFields());
    }

    /** 三个固定字段 + 一个动态字段都给上的推送入参（fail-open 用例要验「照写」） */
    private static JiraPushRequest fullRequest() {
        return new JiraPushRequest(7L, "PROJ", "10001", "支持导出对账单", "原始描述", BACKLINK,
                "High", "lisi", List.of("ai", "账单"), "2026-10-31",
                Map.of("customfield_99999", "x"));
    }

    @Test
    void 元数据拉不到时固定字段与动态字段照常写() {
        // 前端拉不到元数据会降级显示全部输入项，后端再跟着丢字段就成了两处都不敢写
        connector.listCreateFieldsFails = true;

        service.push("p1", "req-local", fullRequest());

        IssueSpec spec = connector.created.get(0);
        assertEquals(List.of("ai", "账单"), spec.labels());
        assertEquals("High", spec.priorityName());
        assertEquals(Map.of("customfield_99999", "x"), spec.extraFields());
    }

    @Test
    void 元数据为空清单时视作未知而不是界面为空() {
        // createmeta 至少含 summary/issuetype，真返回空只说明连接器没给出可用信息；
        // 按空集裁剪会把所有字段连必填的一起丢光，比不裁更糟
        connector.createFields = List.of();

        service.push("p1", "req-local", fullRequest());

        IssueSpec spec = connector.created.get(0);
        assertEquals(List.of("ai", "账单"), spec.labels());
        assertEquals("High", spec.priorityName());
        assertEquals(Map.of("customfield_99999", "x"), spec.extraFields());
    }

    // ---------------- FR-10 个人推送模板 ----------------

    private static JiraPushTemplateRequest templateRequest(Map<String, Object> extraFields) {
        return new JiraPushTemplateRequest(7L, "PROJ", "10003", "Low", "lisi",
                List.of("ai"), extraFields);
    }

    @Test
    void 模板保存并读回() {
        addUser("u1", "alice");
        loginAs("alice");

        JiraPushTemplateView saved = service.saveMyTemplate(
                templateRequest(Map.of("components", List.of(Map.of("id", "10000")))));

        assertEquals(7L, saved.integrationId());
        assertEquals("公司 Jira", saved.integrationName());   // 展示名随实例带出
        assertEquals("PROJ", saved.jiraProjectKey());
        assertEquals("10003", saved.issueTypeId());
        assertEquals("Low", saved.priorityName());
        assertEquals("lisi", saved.assigneeName());
        assertEquals(List.of("ai"), saved.labels());
        assertEquals(Map.of("components", List.of(Map.of("id", "10000"))), saved.extraFields());

        List<JiraPushTemplateView> list = service.listMyTemplates();
        assertEquals(1, list.size());
        assertEquals(saved.id(), list.get(0).id());
    }

    @Test
    void 同组合再保存整行覆盖而不是新增() {
        addUser("u1", "alice");
        loginAs("alice");
        service.saveMyTemplate(templateRequest(Map.of("components", List.of(Map.of("id", "10000")))));
        // 第二次请求不带动态字段与经办人/优先级 → 应被清空，而不是保留上一轮的值
        service.saveMyTemplate(new JiraPushTemplateRequest(7L, "PROJ", "10003",
                null, null, List.of(), null));

        List<JiraPushTemplateView> list = service.listMyTemplates();
        assertEquals(1, list.size());   // 四元组判重：同组合只有一行
        assertNull(list.get(0).assigneeName());
        assertNull(list.get(0).priorityName());
        assertTrue(list.get(0).labels().isEmpty());
        assertTrue(list.get(0).extraFields().isEmpty());
    }

    @Test
    void 不同组合各自成行() {
        addUser("u1", "alice");
        loginAs("alice");
        service.saveMyTemplate(templateRequest(null));
        service.saveMyTemplate(new JiraPushTemplateRequest(7L, "PROJ", "10004",
                null, null, List.of(), null));
        service.saveMyTemplate(new JiraPushTemplateRequest(7L, "ADMQ", "10003",
                null, null, List.of(), null));

        assertEquals(3, service.listMyTemplates().size());
    }

    @Test
    void 模板按用户隔离() {
        addUser("u1", "alice");
        addUser("u2", "bob");
        loginAs("alice");
        JiraPushTemplateView saved = service.saveMyTemplate(templateRequest(null));

        loginAs("bob");
        // 别人的模板看不到、删不掉（按不存在处理，不暴露存在性）
        assertTrue(service.listMyTemplates().isEmpty());
        DevMindException e = assertThrows(DevMindException.class, () -> service.deleteMyTemplate(saved.id()));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());

        loginAs("alice");
        service.deleteMyTemplate(saved.id());
        assertTrue(service.listMyTemplates().isEmpty());
    }

    @Test
    void 未登录时模板读写报401() {
        // 无 SecurityContext 且 users 表没有 local → currentUser 为空
        assertThrows(DevMindException.class, () -> service.saveMyTemplate(templateRequest(null)));
        assertThrows(DevMindException.class, () -> service.listMyTemplates());
    }

    @Test
    void 保存模板缺实例或组合键报400() {
        addUser("u1", "alice");
        loginAs("alice");

        assertThrows(DevMindException.class, () -> service.saveMyTemplate(
                new JiraPushTemplateRequest(null, "PROJ", "10003", null, null, List.of(), null)));
        assertThrows(DevMindException.class, () -> service.saveMyTemplate(
                new JiraPushTemplateRequest(7L, null, "10003", null, null, List.of(), null)));
        assertThrows(DevMindException.class, () -> service.saveMyTemplate(
                new JiraPushTemplateRequest(7L, "PROJ", null, null, null, List.of(), null)));
        assertTrue(templateStore.isEmpty());
    }

    @Test
    void 保存模板时动态字段照推送口径校验() {
        // 复用推送侧同一道护栏：能存进来的取值，推送时一定也能发出去
        addUser("u1", "alice");
        loginAs("alice");

        assertThrows(DevMindException.class, () -> service.saveMyTemplate(
                templateRequest(Map.of("components", Map.of("a", Map.of("b", "c"))))));
        // description 由服务端强制追加回链，不许被模板改写
        assertThrows(DevMindException.class, () -> service.saveMyTemplate(
                templateRequest(Map.of("description", "不许覆盖"))));
        assertTrue(templateStore.isEmpty());
    }

    @Test
    void 模板JSON脏数据当作未配置() {
        // 读侧降级：一条坏配置不该让推送弹窗打不开
        addUser("u1", "alice");
        loginAs("alice");
        service.saveMyTemplate(templateRequest(Map.of("components", List.of(Map.of("id", "10000")))));
        templateStore.values().forEach(t -> t.setExtraFieldsJson("{不是合法 JSON"));

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals(1, view.templates().size());
        assertTrue(view.templates().get(0).extraFields().isEmpty());
        assertEquals("10003", view.templates().get(0).issueTypeId());   // 其余字段不受影响
    }

    @Test
    void targets带回当前用户模板() {
        addUser("u1", "alice");
        loginAs("alice");
        service.saveMyTemplate(templateRequest(Map.of("components", List.of(Map.of("id", "10000")))));

        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertEquals(1, view.templates().size());
        JiraPushTargetsView.TemplateRef t = view.templates().get(0);
        assertEquals(7L, t.integrationId());
        assertEquals("PROJ", t.jiraProjectKey());
        assertEquals("10003", t.issueTypeId());
        assertEquals("lisi", t.assigneeName());
        assertEquals(Map.of("components", List.of(Map.of("id", "10000"))), t.extraFields());
    }

    @Test
    void 无登录用户时targets不带模板() {
        // 异步线程/系统身份没有用户上下文：无模板可带，其余字段不受影响
        JiraPushTargetsView view = service.targets("p1", "req-local");

        assertTrue(view.templates().isEmpty());
        assertEquals(7L, view.defaultIntegrationId());
    }

    // ---------------- FR-10 个人作用域选项端点（配置页无需求/项目上下文） ----------------

    @Test
    void 个人作用域选项端点不依赖需求与项目() {
        connector.projects = List.of(new ExternalProject("PROJ", "对账系统", "http://jira.local/PROJ", "main"));
        connector.issueTypes = List.of(new IssueTypeRef("10003", "任务", false));
        connector.priorities = List.of(new PriorityRef("2", "High"));
        connector.assignableUsers = List.of(new UserRef("lisi", "李四"));

        JiraPushOptionsView opts = service.optionsMine(7L, "PROJ");
        assertEquals(1, opts.jiraProjects().size());
        assertEquals(1, opts.issueTypes().size());
        assertEquals(1, opts.priorities().size());

        List<JiraAssignableUserView> users = service.assignableUsersMine(7L, "PROJ", "李");
        assertEquals(List.of("lisi"), users.stream().map(JiraAssignableUserView::name).toList());
    }

    @Test
    void 个人作用域必填字段无需求上下文时不预填() {
        requiredFieldsLikeRealJira();
        requirementService.store.get("req-local").setFixVersions("1.0,2.0");

        JiraCreateFieldsView fromRequirement = service.createFields("p1", "req-local", 7L, "PROJ", "10003");
        JiraCreateFieldsView fromMine = service.createFieldsMine(7L, "PROJ", "10003");

        // 字段清单与推送弹窗完全一致（渲染不了的照样单独列出）
        assertEquals(fromRequirement.fields().stream().map(JiraCreateFieldView::id).toList(),
                fromMine.fields().stream().map(JiraCreateFieldView::id).toList());
        assertEquals(fromRequirement.requiredFixed(), fromMine.requiredFixed());
        // 唯一的差别：配置页没有本地需求，fixVersions 无从命中
        assertEquals(Map.of("fixVersions", List.of("10100", "10101")), fromRequirement.prefill());
        assertTrue(fromMine.prefill().isEmpty());
    }

    @Test
    void 个人作用域必填字段拉取失败照推送口径降级() {
        connector.listCreateFieldsFails = true;

        JiraCreateFieldsView view = service.createFieldsMine(7L, "PROJ", "10003");

        assertTrue(view.fields().isEmpty());
        assertNotNull(view.error());
    }
}
