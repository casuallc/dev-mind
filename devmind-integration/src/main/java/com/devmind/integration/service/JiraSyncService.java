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
import com.devmind.integration.connector.jira.JiraIssueMapper;
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
import com.devmind.project.dto.RequirementRequest;
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
 * issue → Requirement（DRAFT）经 RequirementService 落主线，external_links 登记幂等；
 * 增量水印 = 已处理的最大 issue updated（回拨 overlap），失败不推进，重复由幂等兜住。
 * 已进入流程（非 DRAFT）的需求不再被 Jira 侧更新覆盖——人工已接管。
 */
@Service
public class JiraSyncService {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncService.class);

    /** 拉取字段清单（Jira /search fields 参数） */
    static final String ISSUE_FIELDS = "summary,description,issuetype,priority,labels,status,created,updated,reporter";

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
        e.setFirstSyncDays(normalizeFirstSyncDays(req.firstSyncDays()));
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
            String key = req.jiraProjectKey().trim().toUpperCase();
            if (!key.equals(e.getJiraProjectKey())) {
                e.setJiraProjectKey(key);
                // 换项目 = 同步范围变化，水位线清零重拉（幂等由 external_links 兜住）
                e.setLastWatermark(null);
            }
        }
        if (req.jql() != null) {
            e.setJql(blankToNull(req.jql()));
        }
        if (req.firstSyncDays() != null) {
            // 仅影响下一次无水印首轮（如换项目清零后）；已有水印的增量轮询不受影响
            e.setFirstSyncDays(normalizeFirstSyncDays(req.firstSyncDays()));
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

    /** 核心同步：拼 JQL → 分页拉取 → 逐 issue upsert → 逐页推进水印。任何异常收敛为失败结果，不抛出。 */
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
            String jql = buildJql(cfg);
            while (pages < JiraSyncConfigEntity.MAX_PAGES_PER_RUN) {
                IssuePage page = connector.searchIssues(integration, token,
                        new IssueQuery(jql, startAt, JiraSyncConfigEntity.PAGE_SIZE, ISSUE_FIELDS));
                pages++;
                if (page.issues().isEmpty()) {
                    break;
                }
                Instant pageMaxUpdated = null;
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
                    if (issue.updated() != null && (pageMaxUpdated == null || issue.updated().isAfter(pageMaxUpdated))) {
                        pageMaxUpdated = issue.updated();
                    }
                }
                // 整页处理完才推进水印（回拨 overlap 防边界漏单）
                if (pageMaxUpdated != null) {
                    cfg.setLastWatermark(pageMaxUpdated.minusSeconds(JiraSyncConfigEntity.WATERMARK_OVERLAP_SECONDS));
                    configRepo.save(cfg);
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
            RequirementView req = requirementService.create(cfg.getProjectId(),
                    new RequirementRequest(renderTitle(issue),
                            renderDescription(integration, issue), null, null, requirementType(issue)));
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
        // 仅 DRAFT 需求允许被 Jira 侧刷新；进入流程后人工已接管，不再覆盖
        if (ExternalLinkEntity.INTERNAL_REQUIREMENT.equals(link.getInternalType())) {
            try {
                RequirementEntity req = requirementService.requireEntity(cfg.getProjectId(), link.getInternalId());
                if (RequirementEntity.STATUS_DRAFT.equals(req.getStatus())) {
                    requirementService.update(cfg.getProjectId(), req.getId(),
                            new RequirementRequest(renderTitle(issue),
                                    renderDescription(integration, issue), null, null, requirementType(issue)));
                    return UpsertOutcome.UPDATED;
                }
            } catch (DevMindException e) {
                // 需求已删除等：保留 link 追溯，不再重建
                log.info("Jira issue {} 对应需求不可用（{}），跳过刷新", issue.key(), e.getMessage());
            }
        }
        return UpsertOutcome.SKIPPED;
    }

    enum UpsertOutcome { IMPORTED, UPDATED, SKIPPED }

    /** JQL 拼装（独立可测）：project 限定 + 用户附加片段 + 增量水印/首轮窗口 + 排序 */
    static String buildJql(JiraSyncConfigEntity cfg) {
        StringBuilder jql = new StringBuilder("project = ").append(cfg.getJiraProjectKey());
        if (cfg.getJql() != null && !cfg.getJql().isBlank()) {
            jql.append(" AND (").append(cfg.getJql().trim()).append(")");
        }
        if (cfg.getLastWatermark() != null) {
            jql.append(" AND updated >= \"")
                    .append(JiraIssueMapper.toJqlTimeLiteral(cfg.getLastWatermark())).append("\"");
            jql.append(" ORDER BY updated asc");
        } else {
            // 首轮：默认只拉近 N 天有更新的 issue（防老项目全量灌入）；0 = 不限全量，限页防爆量
            if (cfg.getFirstSyncDays() > 0) {
                jql.append(" AND updated >= -").append(cfg.getFirstSyncDays()).append("d");
            }
            jql.append(" ORDER BY created asc");
        }
        return jql.toString();
    }

    /** 需求标题：[PROJ-123] summary（requirements.title 列长 256，留前缀余量截到 240） */
    static String renderTitle(JiraIssue issue) {
        String title = "[" + issue.key() + "] " + (issue.summary() == null ? "" : issue.summary().trim());
        return title.length() <= 240 ? title : title.substring(0, 240);
    }

    /** Jira issue type → 需求类型（Bug→BUG，Improvement→IMPROVEMENT，Task/Sub-task→TASK，其余 Story/Epic/未知→FEATURE） */
    static String requirementType(JiraIssue issue) {
        String t = issue.issueType() == null ? "" : issue.issueType().trim().toUpperCase(java.util.Locale.ROOT);
        if (t.contains("BUG")) return RequirementEntity.TYPE_BUG;
        if (t.contains("IMPROVEMENT")) return RequirementEntity.TYPE_IMPROVEMENT;
        if (t.contains("TASK")) return RequirementEntity.TYPE_TASK;
        return RequirementEntity.TYPE_FEATURE;
    }

    /** 需求描述：Jira description（wiki 纯文本）原文 + 来源元信息尾注 */
    static String renderDescription(IntegrationEntity integration, JiraIssue issue) {
        StringBuilder sb = new StringBuilder();
        if (issue.description() != null && !issue.description().isBlank()) {
            sb.append(issue.description().trim());
        }
        sb.append("\n\n---\n> 来源 Jira：").append(browseUrl(integration, issue.key()));
        appendMeta(sb, "类型", issue.issueType());
        appendMeta(sb, "优先级", issue.priority());
        appendMeta(sb, "状态", issue.status());
        appendMeta(sb, "报告人", issue.reporter());
        if (issue.labels() != null && !issue.labels().isEmpty()) {
            appendMeta(sb, "标签", String.join(", ", issue.labels()));
        }
        return sb.toString().trim();
    }

    private static void appendMeta(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(" · ").append(label).append(" ").append(value);
        }
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

    /** 首轮窗口归一：null=默认 7 天；0=不限；负数非法 */
    private int normalizeFirstSyncDays(Integer days) {
        int v = days == null ? JiraSyncConfigEntity.DEFAULT_FIRST_SYNC_DAYS : days;
        if (v < 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "firstSyncDays 不能为负数（0 = 不限）");
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
                e.getPollIntervalSec(), e.getFirstSyncDays(), e.getLastSyncAt(), e.getLastWatermark(),
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
