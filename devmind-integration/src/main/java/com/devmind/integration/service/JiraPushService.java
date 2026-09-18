package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.CreateFieldRef;
import com.devmind.integration.connector.IntegrationConnector.ExternalProject;
import com.devmind.integration.connector.IntegrationConnector.IssueRef;
import com.devmind.integration.connector.IntegrationConnector.IssueSpec;
import com.devmind.integration.connector.IntegrationConnector.IssueTypeRef;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.connector.IntegrationConnector.PriorityRef;
import com.devmind.integration.dto.JiraAssignableUserView;
import com.devmind.integration.dto.JiraCreateFieldView;
import com.devmind.integration.dto.JiraCreateFieldsView;
import com.devmind.integration.dto.JiraOptionView;
import com.devmind.integration.dto.JiraPushOptionsView;
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
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CAP-47 自建需求手动推送到 Jira：候选项/默认值查询、推送（创建 issue + 登记 external_links + 转 Jira 托管）、
 * 按 issue 手动刷新托管字段。
 *
 * <p>与 CAP-19 单向拉取互补——本服务是平台侧唯一「按需创建 issue」的通道，只由用户显式触发，不做自动推送。
 * 写身份走 CAP-35 链（个人账号 → 机器人凭证 → 400 引导绑定）；读选项用**同一个身份**，
 * 保证「能选到的任务类型」与「能创建的任务类型」是同一账号的权限视图。
 *
 * <p>托管字段边界（FR-04）：推送当刻**只**把需求转成 {@code source=JIRA} + 落 {@code externalKey}，
 * 不套用同步的 12 个托管字段——{@code fixVersions}/{@code reporter} 不在推送参数内，
 * 立即套用会拿 Jira 空值把本地已填值静默清空。托管字段由同步覆盖或 {@link #refresh} 收敛。
 */
@Service
public class JiraPushService {

    private static final Logger log = LoggerFactory.getLogger(JiraPushService.class);

    /** integration_calls.action：创建 issue（CAP-47 FR-03） */
    static final String ACTION_CREATE_ISSUE = "create_issue";
    /** integration_calls.action：按 issue 手动刷新（CAP-47 FR-05） */
    static final String ACTION_REFRESH = "jira_refresh";

    /** 无可用写身份（个人账号与机器人凭证皆无） */
    static final String IDENTITY_NONE = "NONE";

    private static final int MAX_SUMMARY = 255;
    private static final int MAX_BACKLINK = 512;

    /** FR-08：固定表单已提供输入项的字段 id（{@code summary}…{@code duedate} 即连接器写的那 8 个） */
    private static final Set<String> FIXED_FIELD_IDS = Set.of(
            "summary", "description", "priority", "assignee", "labels", "duedate");

    /**
     * FR-08：固定字段中**无条件必填**的那些（标题由平台侧本就必填，前后端都拦了空值）。
     * Jira 说它必填时不构成新增约束，回给前端只会多出一句多余的提示，故从 {@code requiredFixed} 里剔除。
     */
    private static final Set<String> FIXED_ALWAYS_REQUIRED = Set.of("summary");

    /** FR-08：动态字段不许覆盖的字段 id = 固定字段 + 连接器自行组装的 project/issuetype + Jira 自动回填的 reporter */
    private static final Set<String> RESERVED_FIELD_IDS = Set.of(
            "project", "issuetype", "summary", "description", "priority", "assignee", "labels", "duedate", "reporter");

    private final IntegrationRepository integrationRepo;
    private final JiraSyncConfigRepository configRepo;
    private final ExternalLinkRepository linkRepo;
    private final IntegrationService integrationService;
    private final RequirementService requirementService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final List<IntegrationConnector> connectorList;
    private final JiraWriteGuard writeGuard;

    public JiraPushService(IntegrationRepository integrationRepo,
                           JiraSyncConfigRepository configRepo,
                           ExternalLinkRepository linkRepo,
                           IntegrationService integrationService,
                           RequirementService requirementService,
                           IdentityService identityService,
                           AuditService auditService,
                           DomainEventPublisher eventPublisher,
                           List<IntegrationConnector> connectorList,
                           JiraWriteGuard writeGuard) {
        this.integrationRepo = integrationRepo;
        this.configRepo = configRepo;
        this.linkRepo = linkRepo;
        this.integrationService = integrationService;
        this.requirementService = requirementService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.connectorList = connectorList;
        this.writeGuard = writeGuard;
    }

    // ---------------- FR-02 候选项与默认值 ----------------

    /**
     * 弹窗打开时的一次性数据源。候选实例/默认目标上的选项拉取失败**不抛错**——
     * 降级为空表 + {@code optionsError}，保证弹窗一定打得开（用户可换实例再试）；
     * 用户主动切换实例/项目后走 {@link #options}，那里失败即抛（须看到真实错误）。
     */
    public JiraPushTargetsView targets(String projectId, String requirementId) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        List<IntegrationEntity> instances = integrationRepo.findByTypeAndStatus(
                IntegrationEntity.TYPE_JIRA, IntegrationEntity.STATUS_ENABLED);
        JiraSyncConfigEntity cfg = defaultConfig(projectId, instances);
        IntegrationEntity target = cfg != null
                ? instances.stream().filter(i -> i.getId().equals(cfg.getIntegrationId())).findFirst().orElse(null)
                : (instances.isEmpty() ? null : instances.get(0));
        String defaultKey = cfg == null ? null : cfg.getJiraProjectKey();

        List<JiraOptionView> projects = List.of();
        List<JiraOptionView> issueTypes = List.of();
        List<JiraOptionView> priorities = List.of();
        String identitySource = IDENTITY_NONE;
        String optionsError = null;
        if (target != null) {
            IdentityProbe probe = probeIdentity(target);
            if (probe.identity() == null) {
                // 个人账号与机器人凭证都没有 → 推送必失败，不必再去调连接器；直接把 CAP-35 的引导原文给前端
                optionsError = probe.reason();
            } else {
                identitySource = probe.identity().source().name();
                try {
                    String token = probe.identity().secret();
                    projects = toProjectOptions(connector().listProjects(target, token));
                    priorities = toPriorityOptions(connector().listPriorities(target, token));
                    if (!blank(defaultKey)) {
                        issueTypes = toIssueTypeOptions(connector().listIssueTypes(target, token, defaultKey));
                    }
                } catch (Exception e) {
                    // 降级：弹窗仍打开，错误原文交给前端提示（用户可换实例再试）
                    optionsError = e.getMessage();
                    log.warn("Jira 推送选项预拉失败（降级为空表）: integration={} err={}",
                            target.getId(), e.getMessage());
                }
            }
        }
        return new JiraPushTargetsView(
                instances.stream()
                        .map(i -> new JiraPushTargetsView.Instance(i.getId(), i.getName(), i.getBaseUrl()))
                        .toList(),
                target == null ? null : target.getId(),
                defaultKey, projects, issueTypes, priorities, defaults(requirement, priorities), identitySource,
                target != null
                        && syncCovered(projectId, target.getId(), defaultKey, requirement.getExternalKey()),
                optionsError);
    }

    /** 切换实例/项目后重拉选项；任一连接器调用失败即抛出（用户主动操作，错误原文透出） */
    public JiraPushOptionsView options(String projectId, String requirementId,
                                       Long integrationId, String jiraProjectKey) {
        requirementService.requireEntity(projectId, requirementId);
        IntegrationEntity integration = requireJira(integrationId);
        String token = readToken(integration);
        String key = trimToNull(jiraProjectKey);
        return new JiraPushOptionsView(
                toProjectOptions(connector().listProjects(integration, token)),
                key == null ? List.of() : toIssueTypeOptions(connector().listIssueTypes(integration, token, key)),
                toPriorityOptions(connector().listPriorities(integration, token)));
    }

    /** 经办人搜索（FR-02）；调用失败由前端降级为纯文本输入，不阻断提交 */
    public List<JiraAssignableUserView> assignableUsers(String projectId, String requirementId,
                                                        Long integrationId, String jiraProjectKey, String q) {
        requirementService.requireEntity(projectId, requirementId);
        IntegrationEntity integration = requireJira(integrationId);
        return connector()
                .listAssignableUsers(integration, readToken(integration), trimToNull(jiraProjectKey), trimToNull(q))
                .stream()
                .map(u -> new JiraAssignableUserView(u.name(), u.displayName()))
                .toList();
    }

    /**
     * FR-08：选定「实例 + 项目 + 任务类型」后的必填字段清单。Jira 的 issue 类型可以配一堆必填字段
     * （模块/影响版本/修复版本/时间跟踪/自定义字段），**平台侧没有这些数据源**（不存模块与影响版本），
     * 只靠固定表单必然被 400 拒——所以这里把「要填什么」问出来，交给弹窗动态渲染。
     *
     * <p>只列**必填且平台无默认值**的字段（有默认值的平台自填，不必打扰用户），并分三区：
     * 固定表单已有的（前端加必填校验）/ 需要新渲染的 / 渲染不了的（列出并禁用提交）。
     * 元数据拉取失败**不抛错**：降级为空表 + {@code error}，提交不禁用——读接口不可用不该把
     * 原本能推的类型也堵死（与 {@link #targets} 同款口径）。
     */
    public JiraCreateFieldsView createFields(String projectId, String requirementId, Long integrationId,
                                            String jiraProjectKey, String issueTypeId) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        IntegrationEntity integration = requireJira(integrationId);
        String key = requireText(jiraProjectKey, "jiraProjectKey（Jira 项目 key）");
        String typeId = requireText(issueTypeId, "issueTypeId（任务类型）");
        List<CreateFieldRef> refs;
        try {
            refs = connector().listCreateFields(integration, readToken(integration), key, typeId);
        } catch (Exception e) {
            log.warn("Jira 创建字段元数据拉取失败（降级为空表，提交不禁用）: integration={} project={} type={} err={}",
                    integration.getId(), key, typeId, e.getMessage());
            return new JiraCreateFieldsView(List.of(), List.of(), List.of(), Map.of(), e.getMessage());
        }
        List<JiraCreateFieldView> fields = new ArrayList<>();
        List<String> requiredFixed = new ArrayList<>();
        List<JiraCreateFieldView> unsupported = new ArrayList<>();
        Map<String, List<String>> prefill = new LinkedHashMap<>();
        for (CreateFieldRef ref : refs) {
            // 有默认值的必填字段平台会自填（hasDefaultValue 即 Jira 的「字段配置有默认值」），不必让用户填
            if (!ref.required() || ref.hasDefault()) {
                continue;
            }
            if (FIXED_FIELD_IDS.contains(ref.id())) {
                if (!FIXED_ALWAYS_REQUIRED.contains(ref.id())) {
                    requiredFixed.add(ref.id());
                }
                continue;
            }
            // Platform manages these (project/issuetype) or Jira auto-fills (reporter) —
            // silently skip instead of putting them into unsupported and blocking submit
            if (RESERVED_FIELD_IDS.contains(ref.id())) {
                continue;
            }
            List<JiraOptionView> options = ref.allowedValues().stream()
                    .map(o -> new JiraOptionView(o.id(), o.value()))
                    .toList();
            String control = controlOf(ref.type(), ref.items(), !options.isEmpty());
            JiraCreateFieldView view = new JiraCreateFieldView(ref.id(), ref.name(), control, options);
            if (control == null) {
                unsupported.add(view);
                continue;
            }
            fields.add(view);
            List<String> pre = prefillFor(requirement, ref.id(), options);
            if (!pre.isEmpty()) {
                prefill.put(ref.id(), pre);
            }
        }
        return new JiraCreateFieldsView(fields, requiredFixed, unsupported, prefill, null);
    }

    /**
     * FR-08 字段类型 → 渲染控件。只认平台真能表达的那些：多选（组件/版本/选项/自由文本数组）、
     * 下拉、日期、文本、数字、时间跟踪。返回 null 即渲染不了，交给前端列出并禁用提交——
     * 必填的用户选择器/级联选择若硬塞一个文本框，用户填什么都会被 Jira 拒，不如提前说清。
     *
     * <p>下拉**必须有候选值**才算可渲染：级联选择等字段在 createmeta 里就是「option 类型但无
     * allowedValues」，塞成自由文本会误导用户。
     */
    static String controlOf(String type, String items, boolean hasOptions) {
        if ("array".equals(type)) {
            boolean renderableItem = "component".equals(items) || "version".equals(items)
                    || "string".equals(items) || "option".equals(items);
            if (!renderableItem || ("option".equals(items) && !hasOptions)) {
                return null;
            }
            // 无候选的 array<string> → 前端渲染成可自由输入的标签框
            return JiraCreateFieldView.CONTROL_MULTI_SELECT;
        }
        if ("option".equals(type)) {
            return hasOptions ? JiraCreateFieldView.CONTROL_SELECT : null;
        }
        if ("date".equals(type)) {
            return JiraCreateFieldView.CONTROL_DATE;
        }
        if ("string".equals(type)) {
            return JiraCreateFieldView.CONTROL_TEXT;
        }
        if ("number".equals(type)) {
            return JiraCreateFieldView.CONTROL_NUMBER;
        }
        if ("timetracking".equals(type)) {
            return JiraCreateFieldView.CONTROL_TIMETRACKING;
        }
        return null;
    }

    /**
     * FR-08 预填：只回填**与 Jira 同域且命中实例候选值**的本地值（FR-02 的同域口径）。
     * 目前只有 {@code fixVersions}——平台存的版本名本就来自 Jira；模块/影响版本平台不存，无可回填。
     * 命中判据用 name 或 id 全等（不做模糊匹配：猜错版本比留空更糟）。
     */
    private static List<String> prefillFor(RequirementEntity requirement, String fieldId,
                                           List<JiraOptionView> options) {
        if (!"fixVersions".equals(fieldId) || options.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String local : splitCsv(requirement.getFixVersions())) {
            String name = local.trim();
            options.stream()
                    .filter(o -> name.equals(o.name()) || name.equals(o.id()))
                    .map(JiraOptionView::id)
                    .findFirst()
                    .ifPresent(id -> {
                        if (!out.contains(id)) {
                            out.add(id);
                        }
                    });
        }
        return out;
    }

    // ---------------- FR-03 推送 ----------------

    /**
     * 推送自建需求到 Jira：创建 issue → 单条回读 → 登记 link → 转 Jira 托管。
     *
     * <p>{@code synchronized}：需求级并发会产出双 issue/双 link（external_links 无唯一约束），
     * 锁内重查幂等。{@link JiraWriteGuard} 另行关闭与同步导入的竞态（见该类的锁边界说明）。
     */
    public synchronized JiraPushResultView push(String projectId, String requirementId, JiraPushRequest req) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        // 幂等检查在来源守卫之前：推送成功会同时置 source=JIRA，若先查来源，
        // 重复点击/陈旧页面只会拿到「已是 JIRA 来源」而看不到关联到哪个 issue，无从追查
        Optional<ExternalLinkEntity> linked = linkRepo
                .findFirstByProjectIdAndInternalTypeAndInternalIdAndExternalTypeOrderByIdDesc(
                        projectId, ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId,
                        ExternalLinkEntity.EXTERNAL_ISSUE);
        if (linked.isPresent() || requirement.getExternalKey() != null) {
            String key = linked.map(ExternalLinkEntity::getExternalKey).orElse(requirement.getExternalKey());
            throw new DevMindException(ErrorCode.CONFLICT, "需求已关联 Jira issue: " + key);
        }
        // 无 link 的 JIRA 来源（同步建的需求未登记 link 等异常态）才落这里
        if (!RequirementEntity.SOURCE_LOCAL.equals(requirement.getSource())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "需求已是 " + requirement.getSource() + " 来源（Jira 托管），无需推送: " + requirementId);
        }
        IntegrationEntity integration = requireJira(req.integrationId());
        // CAP-35 FR-03：人触发写操作个人账号优先，未绑定且有机器人凭证则用机器人，都没有则 400 引导绑定
        IntegrationService.WriteIdentity identity =
                integrationService.resolveWriteIdentity(identityService.currentActor(), integration);
        String token = identity.secret();
        IssueSpec spec = buildSpec(integration, token, requirement, req);

        PushOutcome outcome;
        try {
            outcome = writeGuard.call(() -> createAndLink(projectId, requirement, integration, token, spec));
        } catch (RuntimeException e) {
            // createIssue 失败时本地无任何状态变更；key 冲突/回读失败等分支各自已落库，此处只记调用
            integrationService.recordCall(integration.getId(), ACTION_CREATE_ISSUE,
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
            throw e;
        }
        boolean readback = outcome.issue() != null;
        String callError = readback ? null : "issue 已创建，回读失败：" + outcome.readbackError();
        integrationService.recordCall(integration.getId(), ACTION_CREATE_ISSUE,
                ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, readback, callError);
        boolean syncCovered = syncCovered(projectId, integration.getId(), spec.projectKey(), outcome.ref().key());
        auditService.record("integration", ACTION_CREATE_ISSUE, identityService.currentActor(), projectId, readback,
                "[#" + integration.getId() + "] " + outcome.ref().key() + " 由需求 " + requirementId
                        + " 推送创建（身份 " + identity.source() + "，项目 " + spec.projectKey()
                        + (readback ? "" : "，回读失败") + "）");
        eventPublisher.publish(SimpleDomainEvent.of("integration.jira.pushed", projectId, null,
                identityService.currentActor(),
                readback
                        ? "需求 " + code(requirement.getSeq()) + " 已推送到 Jira " + outcome.ref().key()
                        : "需求 " + code(requirement.getSeq()) + " 已在 Jira 创建 " + outcome.ref().key()
                        + "，但回读失败，请用「从 Jira 刷新」补齐",
                "REQUIREMENT", requirementId, readback));
        if (!readback) {
            // 安全态：link 已登记、source=JIRA 且 externalKey 已落——杜绝「source=JIRA 但 externalKey 为空」
            throw new DevMindException(ErrorCode.INTERNAL,
                    "已在 Jira 创建 " + outcome.ref().key() + "，但拉取失败（" + outcome.readbackError()
                            + "），请稍后在详情页「从 Jira 刷新」补齐托管字段");
        }
        log.info("需求已推送到 Jira: requirement={} code={} key={} identity={}",
                requirementId, code(requirement.getSeq()), outcome.ref().key(), identity.source());
        return new JiraPushResultView(outcome.ref().key(), outcome.ref().url(),
                outcome.issue().status(), outcome.issue().issueType(), syncCovered);
    }

    /**
     * 持锁段：创建 issue → 跨需求去重 → 回读 → 登记 link → 转托管。
     *
     * <p>key 由 Jira 在创建时才给出，窗口无法再收窄——这是推送必须全程持 {@link JiraWriteGuard} 的原因。
     * 回读失败不算致命：link 仍登记（status 留空），需求照样转托管，由调用方引导走手动刷新。
     */
    private PushOutcome createAndLink(String projectId, RequirementEntity requirement, IntegrationEntity integration,
                                      String token, IssueSpec spec) {
        IssueRef ref = connector().createIssue(integration, token, spec);
        // 同一实例下该 key 已属别的需求 → 拒绝登记（正常路径不可能命中：key 由本次创建产生，
        // 且推送与同步导入共用 JiraWriteGuard），此时刚建的 issue 只能由用户在 Jira 侧处理
        linkRepo.findFirstByIntegrationIdAndExternalTypeAndExternalKeyOrderByIdDesc(
                        integration.getId(), ExternalLinkEntity.EXTERNAL_ISSUE, ref.key())
                .filter(l -> !requirement.getId().equals(l.getInternalId()))
                .ifPresent(l -> {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "Jira issue " + ref.key() + " 已关联需求 " + l.getInternalId() + "；本次创建的 "
                                    + ref.key() + " 未登记，请在 Jira 侧处理该 issue");
                });
        JiraIssue issue = null;
        String readbackError = null;
        try {
            issue = connector().getIssue(integration, token, ref.key(), JiraSyncService.ISSUE_FIELDS);
        } catch (Exception e) {
            readbackError = e.getMessage();
            log.warn("Jira issue {} 创建后回读失败（link 仍登记，引导手动刷新）: {}", ref.key(), e.getMessage());
        }
        ExternalLinkEntity link = new ExternalLinkEntity();
        link.setProjectId(projectId);
        link.setIntegrationId(integration.getId());
        link.setInternalType(ExternalLinkEntity.INTERNAL_REQUIREMENT);
        link.setInternalId(requirement.getId());
        link.setExternalType(ExternalLinkEntity.EXTERNAL_ISSUE);
        link.setExternalKey(ref.key());
        link.setExternalUrl(JiraSyncService.browseUrl(integration, ref.key()));
        link.setStatus(issue == null ? null : issue.status());
        link.setCreatedAt(Instant.now());
        linkRepo.save(link);
        // FR-04：只转托管，不套用托管字段（见类注释）
        requirementService.markPushedToJira(projectId, requirement.getId(), ref.key());
        return new PushOutcome(ref, issue, readbackError);
    }

    // ---------------- FR-05 按 issue 手动刷新 ----------------

    /**
     * 按 link 的 externalKey 单条拉回，刷新 link.status + 托管字段（复用同步通道）。
     * **只要求存在 ISSUE link，不要求 source=JIRA**——这是 JQL 不覆盖该 issue 时的兜底通道
     * （注意 {@code syncFromJira} 会把 source 落成 JIRA：link 存在即由 Jira 托管，语义正确）。
     * 本地 status/ownerId/docId 不动。
     */
    public JiraPushResultView refresh(String projectId, String requirementId) {
        requirementService.requireEntity(projectId, requirementId);
        ExternalLinkEntity link = linkRepo
                .findFirstByProjectIdAndInternalTypeAndInternalIdAndExternalTypeOrderByIdDesc(
                        projectId, ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId,
                        ExternalLinkEntity.EXTERNAL_ISSUE)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "需求未关联 Jira issue: " + requirementId));
        IntegrationEntity integration = requireJira(link.getIntegrationId());
        JiraIssue issue;
        try {
            issue = connector().getIssue(integration, readToken(integration),
                    link.getExternalKey(), JiraSyncService.ISSUE_FIELDS);
        } catch (RuntimeException e) {
            integrationService.recordCall(integration.getId(), ACTION_REFRESH,
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
            throw e;
        }
        if (issue == null) {
            String msg = "Jira issue 不存在或不可读: " + link.getExternalKey();
            integrationService.recordCall(integration.getId(), ACTION_REFRESH,
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, msg);
            throw new DevMindException(ErrorCode.NOT_FOUND, msg);
        }
        link.setStatus(issue.status());
        linkRepo.save(link);
        requirementService.syncFromJira(projectId, requirementId, JiraSyncService.managedFields(issue));
        integrationService.recordCall(integration.getId(), ACTION_REFRESH,
                ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, true, null);
        log.info("Jira issue {} 手动刷新完成: requirement={} status={}",
                link.getExternalKey(), requirementId, issue.status());
        return new JiraPushResultView(link.getExternalKey(), link.getExternalUrl(), issue.status(),
                issue.issueType(),
                syncCovered(projectId, integration.getId(), projectKeyOf(link.getExternalKey()),
                        link.getExternalKey()));
    }

    // ---------------- 入参装配与校验 ----------------

    private IssueSpec buildSpec(IntegrationEntity integration, String token,
                                RequirementEntity requirement, JiraPushRequest req) {
        String projectKey = requireText(req.jiraProjectKey(), "jiraProjectKey（Jira 项目 key）")
                .toUpperCase(Locale.ROOT);
        String issueTypeId = requireText(req.issueTypeId(), "issueTypeId（任务类型）");
        String summary = requireText(req.summary(), "summary（标题）");
        if (summary.length() > MAX_SUMMARY) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "标题超长（≤" + MAX_SUMMARY + " 字符）: " + summary.length());
        }
        String backlinkUrl = requireText(req.backlinkUrl(), "backlinkUrl（平台需求回链）");
        if (backlinkUrl.length() > MAX_BACKLINK) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "回链地址超长（≤" + MAX_BACKLINK + " 字符）");
        }
        validatePriority(integration, token, req.priorityName());
        return new IssueSpec(projectKey, issueTypeId, summary,
                composeDescription(req.description(), code(requirement.getSeq()), backlinkUrl),
                trimToNull(req.priorityName()), trimToNull(req.assigneeName()),
                normalizeLabels(req.labels()), parseDueDate(req.dueDate()),
                normalizeExtraFields(req.extraFields()));
    }

    /**
     * FR-08 动态字段护栏。取值语义（{@code [{"id":…}]} / {@code {"originalEstimate":…}}）由前端按
     * {@link JiraCreateFieldView} 的控件类型组装，服务端不重解释——但必须把住两件事：
     *
     * <ol>
     *   <li>**不许覆盖固定字段**：尤其 {@code description}——回链由服务端强制追加，能被覆盖就形同虚设；
     *       {@code project}/{@code issuetype} 被覆盖会推到别的项目/类型上去。</li>
     *   <li>**值形态限两层**：标量、数组（元素为标量或一层扁平对象）、一层扁平对象
     *       （Jira 字段没有更深的结构）。放开等于把任意 JSON 转手发给 Jira，
     *       脏 payload 的错误信息反而更难读。</li>
     * </ol>
     *
     * 空值/空数组条目直接丢弃——沿用「空值字段一律不写进 payload」的既有口径。
     */
    static Map<String, Object> normalizeExtraFields(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String id = trimToNull(entry.getKey());
            if (id == null) {
                continue;
            }
            if (RESERVED_FIELD_IDS.contains(id)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "动态字段不能覆盖由平台管理的字段: " + id);
            }
            Object value = entry.getValue();
            if (value == null || (value instanceof String s && s.isBlank())
                    || (value instanceof Collection<?> c && c.isEmpty())) {
                continue;
            }
            checkExtraFieldShape(id, value);
            out.put(id, value);
        }
        return out;
    }

    private static void checkExtraFieldShape(String id, Object value) {
        if (isScalar(value) || isFlatObject(id, value)) {
            return;
        }
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                if (item == null || isScalar(item) || isFlatObject(id, item)) {
                    continue;
                }
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "动态字段 " + id + " 取值非法：数组元素只能是标量或一层扁平对象");
            }
            return;
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "动态字段 " + id + " 取值非法：只接受标量、数组或一层扁平对象");
    }

    /**
     * 一层扁平对象：键为字符串、值为标量。Jira 的 {@code [{"id":…}]}（组件/版本）与
     * {@code {"originalEstimate":…}}（时间跟踪）都是这形态；再深一层就抛。
     */
    private static boolean isFlatObject(String id, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || (entry.getValue() != null && !isScalar(entry.getValue()))) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "动态字段 " + id + " 取值非法：对象只能是一层标量键值");
            }
        }
        return true;
    }

    private static boolean isScalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    /** FR-03 步骤 4：服务端强制在描述尾部追加平台回链（文案格式只此一处；URL 由前端按 origin 拼） */
    static String composeDescription(String description, String requirementCode, String backlinkUrl) {
        String body = description == null ? "" : description.strip();
        String backlink = requirementCode + " · " + backlinkUrl.strip();
        return body.isEmpty() ? backlink : body + "\n\n" + backlink;
    }

    /** 标签去空去重；含空白或逗号的标签直接拒绝（Jira 不接受空白标签，逗号会撞落列时的 CSV 口径） */
    private static List<String> normalizeLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : labels) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String label = raw.trim();
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (Character.isWhitespace(c) || c == ',') {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "标签不能含空格或逗号: " + label);
                }
            }
            if (!out.contains(label)) {
                out.add(label);
            }
        }
        return out;
    }

    /**
     * 优先级须命中实例词表（FR-03 步骤 2）。词表拉取失败则跳过校验——读接口不可用不该阻断写操作；
     * 词表为空说明实例关闭了优先级功能，同样跳过。
     */
    private void validatePriority(IntegrationEntity integration, String token, String priority) {
        String name = trimToNull(priority);
        if (name == null) {
            return;
        }
        List<PriorityRef> known;
        try {
            known = connector().listPriorities(integration, token);
        } catch (Exception e) {
            log.warn("优先级词表拉取失败，跳过校验: integration={} err={}", integration.getId(), e.getMessage());
            return;
        }
        if (known.isEmpty()) {
            return;
        }
        if (known.stream().noneMatch(p -> name.equals(p.name()))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "优先级不在实例词表内: " + name
                    + "（可用：" + String.join("/", known.stream().map(PriorityRef::name).toList()) + "）");
        }
    }

    private static LocalDate parseDueDate(String raw) {
        String s = trimToNull(raw);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法截止日期: " + s + "（格式 yyyy-MM-dd）");
        }
    }

    // ---------------- 内部 ----------------

    /** 推送结果（createAndLink 出参）：issue 为 null 表示回读失败，readbackError 为原因 */
    private record PushOutcome(IssueRef ref, JiraIssue issue, String readbackError) {
    }

    /** 默认目标：本项目第一条 enabled 同步配置（其集成须在启用实例内）；无则退候选实例首条 */
    private JiraSyncConfigEntity defaultConfig(String projectId, List<IntegrationEntity> instances) {
        return configRepo.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(JiraSyncConfigEntity::isEnabled)
                .filter(c -> instances.stream().anyMatch(i -> i.getId().equals(c.getIntegrationId())))
                .findFirst()
                .orElse(null);
    }

    /** 该项目在此实例上是否有覆盖该 Jira 项目的同步配置 → 托管字段会自动刷新（FR-05 提示用） */
    private boolean syncCovered(String projectId, Long integrationId, String jiraProjectKey, String issueKey) {
        String key = blank(jiraProjectKey) ? projectKeyOf(issueKey) : jiraProjectKey;
        if (integrationId == null || key == null) {
            return false;
        }
        return configRepo.findByIntegrationIdAndProjectId(integrationId, projectId)
                .filter(JiraSyncConfigEntity::isEnabled)
                .filter(c -> key.equalsIgnoreCase(c.getJiraProjectKey()))
                .isPresent();
    }

    /** issue key 前缀即 Jira 项目 key（PROJ-123 → PROJ）；无连字符返回 null */
    static String projectKeyOf(String issueKey) {
        if (issueKey == null) {
            return null;
        }
        int dash = issueKey.lastIndexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : null;
    }

    private IntegrationEntity requireJira(Long integrationId) {
        if (integrationId == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 不能为空");
        }
        IntegrationEntity integration = integrationService.require(integrationId);
        if (!IntegrationEntity.TYPE_JIRA.equals(integration.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成 #" + integrationId + " 不是 JIRA 类型（" + integration.getType() + "）");
        }
        if (!IntegrationEntity.STATUS_ENABLED.equals(integration.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成已禁用: #" + integration.getId() + " " + integration.getName());
        }
        return integration;
    }

    /** 读 token = 推送将用的身份（CAP-35：个人优先 → 机器人）；都没有则抛 CAP-35 的引导原文 */
    private String readToken(IntegrationEntity integration) {
        IdentityProbe probe = probeIdentity(integration);
        if (probe.identity() != null) {
            return probe.identity().secret();
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST, probe.reason());
    }

    /** CAP-35 身份链的可空封装：identity 非空即可推送；为空时 reason 为引导绑定原文 */
    private IdentityProbe probeIdentity(IntegrationEntity integration) {
        try {
            return new IdentityProbe(integrationService
                    .resolveWriteIdentity(identityService.currentActor(), integration), null);
        } catch (DevMindException e) {
            return new IdentityProbe(null, e.getMessage());
        }
    }

    private record IdentityProbe(IntegrationService.WriteIdentity identity, String reason) {
    }

    private JiraPushTargetsView.Defaults defaults(RequirementEntity requirement,
                                                  List<JiraOptionView> priorities) {
        return new JiraPushTargetsView.Defaults(requirement.getTitle(), requirement.getDescription(),
                prefillPriority(requirement.getPriority(), priorities), splitCsv(requirement.getLabels()),
                requirement.getDueDate() == null ? null : requirement.getDueDate().toString());
    }

    /**
     * 优先级回填须**命中实例词表**才给：平台优先级是固定英文枚举（Highest/High/Medium/Low/Lowest），
     * Jira 词表随实例语言包与项目配置（中文实例返回「高/中/低」），两套枚举只是偶尔重合。
     * 不命中就不回填——照填只会被 {@link #validatePriority} 拦成 400，或把平台值当 Jira 值推上去。
     * 词表拉取失败或实例关闭优先级功能时列表为空 → 同样不回填（不做无据推测）。
     */
    static String prefillPriority(String platformPriority, List<JiraOptionView> priorities) {
        if (platformPriority == null || platformPriority.isBlank()) {
            return null;
        }
        String name = platformPriority.trim();
        return priorities.stream()
                .filter(p -> name.equals(p.name()))
                .map(JiraOptionView::name)
                .findFirst()
                .orElse(null);
    }

    /** Jira 项目选项：id=项目 key，name=「KEY 项目名」（无名或同名时只留 key） */
    private static List<JiraOptionView> toProjectOptions(List<ExternalProject> projects) {
        return projects.stream()
                .filter(p -> !blank(p.key()))
                .map(p -> new JiraOptionView(p.key(), blank(p.name()) || p.name().equals(p.key())
                        ? p.key() : p.key() + " " + p.name()))
                .toList();
    }

    /** 任务类型选项：子任务不能作为顶层 issue 创建，过滤掉 */
    private static List<JiraOptionView> toIssueTypeOptions(List<IssueTypeRef> types) {
        return types.stream()
                .filter(t -> !t.subtask())
                .map(t -> new JiraOptionView(t.id(), t.name()))
                .toList();
    }

    private static List<JiraOptionView> toPriorityOptions(List<PriorityRef> priorities) {
        return priorities.stream().map(p -> new JiraOptionView(p.id(), p.name())).toList();
    }

    private IntegrationConnector connector() {
        return connectorList.stream()
                .filter(c -> IntegrationEntity.TYPE_JIRA.equals(c.type()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.INTERNAL, "JIRA 连接器未注册"));
    }

    private static String requireText(String value, String field) {
        String s = trimToNull(value);
        if (s == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        return s;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    private static String code(Long seq) {
        return "REQ-" + seq;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
