package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.connector.IntegrationConnector.IssueQuery;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.dto.JiraSyncConfigRequest;
import com.devmind.integration.dto.JiraSyncConfigView;
import com.devmind.integration.dto.JiraSyncRunView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.JiraSyncConfigEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.JiraSyncConfigRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.JiraManagedFields;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAP-19 Jira 同步：配置 CRUD + 轮询/手动同步执行（单向只拉取，不回写 Jira）。
 * issue → Requirement（DRAFT，source=JIRA）经 RequirementService 同步通道落主线，
 * external_links 登记幂等；每轮严格按创建时所给条件（project + 附加 JQL）拉取，
 * 不加其他过滤，重复由幂等兜住。
 * Jira 托管字段（标题/描述/类型/优先级/经办人/报告人/标签/修复版本/截止日期）始终随同步刷新
 * （本地只读，无覆盖冲突）；本地字段 status/ownerId/docId 同步绝不动。
 */
@Service
public class JiraSyncService {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncService.class);

    /** 拉取字段清单（Jira /search fields 参数）；sprint 是自定义字段（实例间字段名不同），v1 不拉 */
    static final String ISSUE_FIELDS =
            "summary,description,issuetype,priority,labels,status,created,updated,reporter,assignee,fixVersions,duedate";

    private final JiraSyncConfigRepository configRepo;
    private final IntegrationRepository integrationRepo;
    private final ExternalLinkRepository linkRepo;
    private final IntegrationService integrationService;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final List<IntegrationConnector> connectorList;
    /** 自注入代理：upsertIssue 的独立事务边界需要走代理（直接 this 调用会绕过 @Transactional） */
    private final ObjectProvider<JiraSyncService> self;

    /** 全局防重入闸（单实例部署）：tick 与手动同步共用 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public JiraSyncService(JiraSyncConfigRepository configRepo,
                           IntegrationRepository integrationRepo,
                           ExternalLinkRepository linkRepo,
                           IntegrationService integrationService,
                           ProjectService projectService,
                           RequirementService requirementService,
                           IdentityService identityService,
                           AuditService auditService,
                           DomainEventPublisher eventPublisher,
                           List<IntegrationConnector> connectorList,
                           ObjectProvider<JiraSyncService> self) {
        this.configRepo = configRepo;
        this.integrationRepo = integrationRepo;
        this.linkRepo = linkRepo;
        this.integrationService = integrationService;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.connectorList = connectorList;
        this.self = self;
    }

    // ---------------- 配置 CRUD ----------------

    public List<JiraSyncConfigView> list(String projectId) {
        projectService.requireProject(projectId);
        return configRepo.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toView).toList();
    }

    public JiraSyncConfigView create(String projectId, JiraSyncConfigRequest req) {
        projectService.requireProject(projectId);
        if (req.integrationId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 不能为空");
        }
        IntegrationEntity integration = integrationService.require(req.integrationId());
        if (!IntegrationEntity.TYPE_JIRA.equals(integration.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成 #" + req.integrationId() + " 不是 JIRA 类型（" + integration.getType() + "）");
        }
        if (req.jiraProjectKey() == null || req.jiraProjectKey().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "jiraProjectKey 不能为空（Jira 项目 key，如 PROJ）");
        }
        configRepo.findByIntegrationIdAndProjectId(req.integrationId(), projectId).ifPresent(x -> {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "项目已配置该 Jira 实例的同步（配置 #" + x.getId() + "），请直接修改");
        });
        JiraSyncConfigEntity e = new JiraSyncConfigEntity();
        e.setIntegrationId(req.integrationId());
        e.setProjectId(projectId);
        e.setJiraProjectKey(req.jiraProjectKey().trim().toUpperCase());
        e.setJql(blankToNull(req.jql()));
        e.setEnabled(req.enabled() == null || req.enabled());
        e.setPollIntervalSec(normalizeInterval(req.pollIntervalSec()));
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        JiraSyncConfigEntity saved = configRepo.save(e);
        audit("jira_sync_config_create", saved.getIntegrationId(), projectId, true,
                "Jira 同步配置 " + saved.getJiraProjectKey() + " → 项目 " + projectId);
        return toView(saved);
    }

    public JiraSyncConfigView update(String projectId, Long configId, JiraSyncConfigRequest req) {
        JiraSyncConfigEntity e = require(projectId, configId);
        if (req.jiraProjectKey() != null && !req.jiraProjectKey().isBlank()) {
            e.setJiraProjectKey(req.jiraProjectKey().trim().toUpperCase());
        }
        if (req.jql() != null) {
            e.setJql(blankToNull(req.jql()));
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        if (req.pollIntervalSec() != null) {
            e.setPollIntervalSec(normalizeInterval(req.pollIntervalSec()));
        }
        e.setUpdatedAt(Instant.now());
        return toView(configRepo.save(e));
    }

    public void delete(String projectId, Long configId) {
        JiraSyncConfigEntity e = require(projectId, configId);
        configRepo.delete(e);
        audit("jira_sync_config_delete", e.getIntegrationId(), projectId, true,
                "删除 Jira 同步配置 #" + configId + "（" + e.getJiraProjectKey() + "）");
    }

    public JiraSyncConfigEntity require(String projectId, Long configId) {
        return configRepo.findById(configId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "Jira 同步配置不存在: " + configId));
    }

    // ---------------- 同步执行 ----------------

    /** 定时入口：每分钟扫一次配置表，到期（lastSyncAt + pollIntervalSec <= now）的逐配置同步 */
    @Scheduled(fixedDelayString = "${devmind.integration.jira.tick-ms:60000}",
            initialDelayString = "${devmind.integration.jira.initial-delay-ms:30000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            for (JiraSyncConfigEntity cfg : configRepo.findByEnabledTrue()) {
                if (isDue(cfg, now)) {
                    doSync(cfg.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Jira 同步 tick 异常: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** 到期判定（独立可测）：从未跑过或距上次已超过配置间隔 */
    static boolean isDue(JiraSyncConfigEntity cfg, Instant now) {
        return cfg.getLastSyncAt() == null
                || !cfg.getLastSyncAt().plusSeconds(cfg.getPollIntervalSec()).isAfter(now);
    }

    /** 手动触发一次同步（与定时轮询共用核心） */
    public JiraSyncRunView syncNow(String projectId, Long configId) {
        require(projectId, configId); // 归属校验
        if (!running.compareAndSet(false, true)) {
            throw new DevMindException(ErrorCode.CONFLICT, "有同步任务正在进行中，请稍后重试");
        }
        try {
            return doSync(configId);
        } finally {
            running.set(false);
        }
    }

    /** 核心同步：拼 JQL → 分页拉取 → 逐 issue upsert。任何异常收敛为失败结果，不抛出。 */
    public JiraSyncRunView doSync(Long configId) {
        JiraSyncConfigEntity cfg = configRepo.findById(configId).orElse(null);
        if (cfg == null) {
            return new JiraSyncRunView(configId, 0, 0, 0, 0, "配置不存在: " + configId);
        }
        IntegrationEntity integration = integrationRepo.findById(cfg.getIntegrationId()).orElse(null);
        if (integration == null || !IntegrationEntity.STATUS_ENABLED.equals(integration.getStatus())) {
            return finish(cfg, 0, 0, 0, 0, "集成不存在或已禁用: " + cfg.getIntegrationId());
        }
        IntegrationConnector connector = jiraConnector();
        int imported = 0, updated = 0, skipped = 0, pages = 0;
        int startAt = 0;
        try {
            String token = integrationService.tokenOf(integration);
            String jql = buildJql(cfg.getJiraProjectKey(), cfg.getJql());
            while (pages < JiraSyncConfigEntity.MAX_PAGES_PER_RUN) {
                IssuePage page = connector.searchIssues(integration, token,
                        new IssueQuery(jql, startAt, JiraSyncConfigEntity.PAGE_SIZE, ISSUE_FIELDS));
                pages++;
                if (page.issues().isEmpty()) {
                    break;
                }
                for (JiraIssue issue : page.issues()) {
                    try {
                        UpsertOutcome outcome = self.getObject().upsertIssue(cfg, integration, issue);
                        switch (outcome) {
                            case IMPORTED -> imported++;
                            case UPDATED -> updated++;
                            case SKIPPED -> skipped++;
                        }
                    } catch (Exception e) {
                        // 单条失败不阻塞整页
                        skipped++;
                        log.warn("Jira issue {} 同步失败: {}", issue.key(), e.getMessage());
                    }
                }
                startAt += page.issues().size();
                if (startAt >= page.total()) {
                    break;
                }
            }
            return finish(cfg, imported, updated, skipped, pages, null);
        } catch (Exception e) {
            log.warn("Jira 同步失败: config={} err={}", configId, e.getMessage());
            return finish(cfg, imported, updated, skipped, pages, e.getMessage());
        }
    }

    /** 单 issue upsert（独立事务：需求 + link 要么一起落库要么都不落，避免半吊子状态破坏幂等） */
    @Transactional
    public UpsertOutcome upsertIssue(JiraSyncConfigEntity cfg, IntegrationEntity integration, JiraIssue issue) {
        Optional<ExternalLinkEntity> existing = linkRepo
                .findFirstByIntegrationIdAndExternalTypeAndExternalKeyOrderByIdDesc(
                        integration.getId(), ExternalLinkEntity.EXTERNAL_ISSUE, issue.key());
        if (existing.isEmpty()) {
            RequirementView req = requirementService.createFromJira(cfg.getProjectId(), managedFields(issue));
            ExternalLinkEntity link = new ExternalLinkEntity();
            link.setProjectId(cfg.getProjectId());
            link.setIntegrationId(integration.getId());
            link.setInternalType(ExternalLinkEntity.INTERNAL_REQUIREMENT);
            link.setInternalId(req.id());
            link.setExternalType(ExternalLinkEntity.EXTERNAL_ISSUE);
            link.setExternalKey(issue.key());
            link.setExternalUrl(browseUrl(integration, issue.key()));
            link.setStatus(issue.status());
            link.setCreatedAt(Instant.now());
            linkRepo.save(link);
            return UpsertOutcome.IMPORTED;
        }
        ExternalLinkEntity link = existing.get();
        link.setStatus(issue.status());
        linkRepo.save(link);
        // Jira 托管字段始终随同步刷新（本地只读无冲突）；本地字段 status/ownerId/docId 不动
        if (ExternalLinkEntity.INTERNAL_REQUIREMENT.equals(link.getInternalType())) {
            try {
                requirementService.syncFromJira(cfg.getProjectId(), link.getInternalId(), managedFields(issue));
                return UpsertOutcome.UPDATED;
            } catch (DevMindException e) {
                // 需求已删除等：保留 link 追溯，不再重建
                log.info("Jira issue {} 对应需求不可用（{}），跳过刷新", issue.key(), e.getMessage());
            }
        }
        return UpsertOutcome.SKIPPED;
    }

    /** issue → 托管字段包（标题=summary 原文、描述=Jira 原文，无前缀无尾注——元信息全部进列） */
    private static JiraManagedFields managedFields(JiraIssue issue) {
        return new JiraManagedFields(
                issue.summary(), issue.description(), requirementType(issue),
                issue.priority(), issue.assignee(), issue.reporter(),
                issue.labels(), issue.fixVersions(), issue.dueDate(), issue.key());
    }

    enum UpsertOutcome { IMPORTED, UPDATED, SKIPPED }

    /** JQL 拼装（独立可测）：只按创建时所给条件过滤——project 限定 + 用户附加片段，不加任何其他条件 */
    static String buildJql(String jiraProjectKey, String extraJql) {
        StringBuilder jql = new StringBuilder("project = ").append(jiraProjectKey);
        if (extraJql != null && !extraJql.isBlank()) {
            jql.append(" AND (").append(extraJql.trim()).append(")");
        }
        jql.append(" ORDER BY created asc");
        return jql.toString();
    }

    /** Jira issue type → 需求类型（Bug→BUG，Improvement→IMPROVEMENT，Task/Sub-task→TASK，其余 Story/Epic/未知→FEATURE） */
    static String requirementType(JiraIssue issue) {
        String t = issue.issueType() == null ? "" : issue.issueType().trim().toUpperCase(java.util.Locale.ROOT);
        if (t.contains("BUG")) return RequirementEntity.TYPE_BUG;
        if (t.contains("IMPROVEMENT")) return RequirementEntity.TYPE_IMPROVEMENT;
        if (t.contains("TASK")) return RequirementEntity.TYPE_TASK;
        return RequirementEntity.TYPE_FEATURE;
    }

    static String browseUrl(IntegrationEntity integration, String issueKey) {
        return integration.getBaseUrl().replaceAll("/+$", "") + "/browse/" + issueKey;
    }

    /** 收尾：落配置运行状态 + 调用审计 + 有变化或失败时发领域事件（走通知中心） */
    private JiraSyncRunView finish(JiraSyncConfigEntity cfg, int imported, int updated, int skipped,
                                   int pages, String error) {
        boolean success = error == null;
        cfg.setLastSyncAt(Instant.now());
        cfg.setLastImported(imported);
        cfg.setLastUpdatedCount(updated);
        cfg.setLastError(success ? null : truncate(error, 1900));
        cfg.setUpdatedAt(Instant.now());
        configRepo.save(cfg);
        integrationService.recordCall(cfg.getIntegrationId(), "jira_sync", null, null, success,
                success ? null : truncate(error, 1900));
        if (imported > 0 || updated > 0 || !success) {
            String summary = success
                    ? "Jira 同步：" + cfg.getJiraProjectKey() + " 新增 " + imported + " 条需求"
                    + (updated > 0 ? "，刷新 " + updated + " 条" : "")
                    : "Jira 同步失败：" + cfg.getJiraProjectKey() + " " + truncate(error, 200);
            eventPublisher.publish(SimpleDomainEvent.of("integration.jira.synced", cfg.getProjectId(),
                    null, "system", summary, "JIRA_SYNC", String.valueOf(cfg.getId()), success));
        }
        return new JiraSyncRunView(cfg.getId(), imported, updated, skipped, pages, error);
    }

    // ---------------- 内部 ----------------

    private IntegrationConnector jiraConnector() {
        return connectorList.stream()
                .filter(c -> IntegrationEntity.TYPE_JIRA.equals(c.type()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.INTERNAL, "JIRA 连接器未注册"));
    }

    private int normalizeInterval(Integer sec) {
        int v = sec == null ? 300 : sec;
        if (v < 60) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "pollIntervalSec 最小 60 秒");
        }
        return v;
    }

    private void audit(String action, Long integrationId, String projectId, boolean success, String detail) {
        auditService.record("integration", action, identityService.currentActor(),
                projectId, success, integrationId != null ? "[#" + integrationId + "] " + detail : detail);
    }

    private JiraSyncConfigView toView(JiraSyncConfigEntity e) {
        IntegrationEntity integration = integrationRepo.findById(e.getIntegrationId()).orElse(null);
        return new JiraSyncConfigView(e.getId(), e.getIntegrationId(),
                integration != null ? integration.getName() : null,
                e.getProjectId(), e.getJiraProjectKey(), e.getJql(), e.isEnabled(),
                e.getPollIntervalSec(), e.getLastSyncAt(),
                e.getLastImported(), e.getLastUpdatedCount(), e.getLastError(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }
}
