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
import com.devmind.integration.connector.IntegrationConnector.IssueTransition;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.dto.JiraTransitionResultView;
import com.devmind.integration.dto.JiraTransitionView;
import com.devmind.integration.dto.JiraWorklogResultView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CAP-19 FR-08 平台侧 Jira 状态回写：列工作流转换 + 执行转换（写操作仅限 transitions）。
 * 转换清单由 Jira 按 issue 当前状态与工作流动态给出，不硬编码状态名；
 * 执行安全闸：transitionId 必须在当前可用列表内。
 * 边界：只回写远端并即时单条刷新托管字段/remoteStatus，本地需求 status 绝不动。
 */
@Service
public class JiraIssueActionService {

    private static final Logger log = LoggerFactory.getLogger(JiraIssueActionService.class);

    private final ExternalLinkRepository linkRepo;
    private final IntegrationService integrationService;
    private final RequirementService requirementService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final List<IntegrationConnector> connectorList;

    public JiraIssueActionService(ExternalLinkRepository linkRepo,
                                  IntegrationService integrationService,
                                  RequirementService requirementService,
                                  IdentityService identityService,
                                  AuditService auditService,
                                  DomainEventPublisher eventPublisher,
                                  List<IntegrationConnector> connectorList) {
        this.linkRepo = linkRepo;
        this.integrationService = integrationService;
        this.requirementService = requirementService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.connectorList = connectorList;
    }

    /** 需求当前可用的 Jira 工作流转换（详情页「Jira 操作」下拉数据源） */
    public List<JiraTransitionView> listTransitions(String projectId, String requirementId) {
        Ref ref = resolve(projectId, requirementId);
        return jiraConnector()
                .listTransitions(ref.integration(), integrationService.tokenOf(ref.integration()),
                        ref.link().getExternalKey())
                .stream()
                .map(t -> new JiraTransitionView(t.id(), t.name(), t.toStatus()))
                .toList();
    }

    /** 执行转换：安全闸校验 → 回写 Jira → 单条刷新（托管字段 + link.status）→ 审计/事件 */
    public JiraTransitionResultView transit(String projectId, String requirementId, String transitionId) {
        if (transitionId == null || transitionId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "transitionId 不能为空");
        }
        Ref ref = resolve(projectId, requirementId);
        IntegrationConnector connector = jiraConnector();
        String token = integrationService.tokenOf(ref.integration());
        List<IssueTransition> available = connector.listTransitions(ref.integration(), token,
                ref.link().getExternalKey());
        IssueTransition target = available.stream()
                .filter(t -> t.id().equals(transitionId.trim()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "转换不可用（issue 当前状态不允许或 id 已失效），请刷新后重试: " + transitionId));
        try {
            connector.transitionIssue(ref.integration(), token, ref.link().getExternalKey(), target.id());
        } catch (Exception e) {
            integrationService.recordCall(ref.integration().getId(), "jira_transition",
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
            throw e;
        }
        String remoteStatus = refreshAfterTransit(projectId, requirementId, ref, connector, token);
        integrationService.recordCall(ref.integration().getId(), "jira_transition",
                ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, true, null);
        String actor = identityService.currentActor();
        auditService.record("integration", "jira_transition", actor, projectId, true,
                "[#" + ref.integration().getId() + "] " + ref.link().getExternalKey()
                        + " 执行转换「" + target.name() + "」（需求 " + requirementId + "）");
        eventPublisher.publish(SimpleDomainEvent.of("integration.jira.transitioned", projectId,
                null, actor,
                "Jira " + ref.link().getExternalKey() + " 已执行转换「" + target.name() + "」",
                "REQUIREMENT", requirementId, true));
        log.info("Jira issue {} 转换已执行: {} -> {}", ref.link().getExternalKey(), target.name(), target.toStatus());
        return new JiraTransitionResultView(
                new JiraTransitionView(target.id(), target.name(), target.toStatus()), remoteStatus);
    }

    /** 登记工时（CAP-27）：写 Jira worklog → 单条刷新（spentSeconds 回落）→ 审计/事件 */
    public JiraWorklogResultView logWork(String projectId, String requirementId, Long seconds, String comment) {
        if (seconds == null || seconds <= 0 || seconds > 360_000) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "工时秒数非法（1~360000）: " + seconds);
        }
        Ref ref = resolve(projectId, requirementId);
        IntegrationConnector connector = jiraConnector();
        String token = integrationService.tokenOf(ref.integration());
        try {
            connector.logWork(ref.integration(), token, ref.link().getExternalKey(), seconds, comment);
        } catch (Exception e) {
            integrationService.recordCall(ref.integration().getId(), "jira_worklog",
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
            throw e;
        }
        String remoteStatus = refreshAfterTransit(projectId, requirementId, ref, connector, token);
        integrationService.recordCall(ref.integration().getId(), "jira_worklog",
                ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, true, null);
        String actor = identityService.currentActor();
        auditService.record("integration", "jira_worklog", actor, projectId, true,
                "[#" + ref.integration().getId() + "] " + ref.link().getExternalKey()
                        + " 登记工时 " + seconds + "s（需求 " + requirementId + "）");
        eventPublisher.publish(SimpleDomainEvent.of("integration.jira.worklogged", projectId,
                null, actor,
                "Jira " + ref.link().getExternalKey() + " 已登记工时 " + formatSeconds(seconds),
                "REQUIREMENT", requirementId, true));
        log.info("Jira issue {} 工时已登记: {}s", ref.link().getExternalKey(), seconds);
        return new JiraWorklogResultView(seconds, remoteStatus);
    }

    /** 秒数 → 简报文案（1.5h / 45m） */
    private static String formatSeconds(long seconds) {
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        return String.format(java.util.Locale.ROOT, "%.1fh", seconds / 3600.0);
    }

    /** 转换后单条刷新：按 key 精确拉回 issue，刷新 link.status 与托管字段（本地 status 不动） */
    private String refreshAfterTransit(String projectId, String requirementId, Ref ref,
                                       IntegrationConnector connector, String token) {
        try {
            IssuePage page = connector.searchIssues(ref.integration(), token,
                    new IssueQuery("key = " + ref.link().getExternalKey(), 0, 1,
                            JiraSyncService.ISSUE_FIELDS));
            if (page.issues().isEmpty()) {
                return ref.link().getStatus();
            }
            JiraIssue issue = page.issues().get(0);
            ref.link().setStatus(issue.status());
            linkRepo.save(ref.link());
            requirementService.syncFromJira(projectId, requirementId, JiraSyncService.managedFields(issue));
            return issue.status();
        } catch (Exception e) {
            // 刷新失败不回滚转换——远端已生效，下一轮同步会补齐
            log.warn("Jira issue {} 转换后刷新失败（下轮同步补齐）: {}", ref.link().getExternalKey(), e.getMessage());
            return ref.link().getStatus();
        }
    }

    /** 公共解析：需求（须 JIRA 来源）→ external_links 反查 issue → 集成（须启用） */
    private Ref resolve(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        if (!RequirementEntity.SOURCE_JIRA.equals(req.getSource())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "需求非 Jira 来源，无 Jira 操作: " + requirementId);
        }
        ExternalLinkEntity link = linkRepo.findByProjectIdAndInternalTypeAndInternalId(
                        projectId, ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId)
                .stream()
                .filter(l -> ExternalLinkEntity.EXTERNAL_ISSUE.equals(l.getExternalType()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "需求未关联 Jira issue: " + requirementId));
        IntegrationEntity integration = integrationService.require(link.getIntegrationId());
        if (!IntegrationEntity.STATUS_ENABLED.equals(integration.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成已禁用: #" + integration.getId() + " " + integration.getName());
        }
        return new Ref(req, link, integration);
    }

    private record Ref(RequirementEntity requirement, ExternalLinkEntity link, IntegrationEntity integration) {
    }

    private IntegrationConnector jiraConnector() {
        return connectorList.stream()
                .filter(c -> IntegrationEntity.TYPE_JIRA.equals(c.type()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.INTERNAL, "JIRA 连接器未注册"));
    }
}
