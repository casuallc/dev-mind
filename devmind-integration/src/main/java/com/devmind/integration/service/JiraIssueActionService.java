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
import com.devmind.integration.dto.JiraResolveResultView;
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

    /**
     * CAP-19 FR-09：需求关联 issue 的附件内容（描述 wiki 图片标记的按需代理）。
     * 读操作，与 listTransitions 同口径——不 recordCall 不审计。
     */
    public IntegrationConnector.IssueAttachment loadAttachment(String projectId, String requirementId,
                                                             String filename) {
        if (filename == null || filename.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件文件名不能为空");
        }
        Ref ref = resolve(projectId, requirementId);
        return jiraConnector().fetchIssueAttachment(ref.integration(),
                integrationService.tokenOf(ref.integration()), ref.link().getExternalKey(), filename.trim());
    }

    /** 执行转换：安全闸校验 → 回写 Jira → 单条刷新（托管字段 + link.status）→ 审计/事件 */
    public JiraTransitionResultView transit(String projectId, String requirementId, String transitionId) {
        if (transitionId == null || transitionId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "transitionId 不能为空");
        }
        Ref ref = resolve(projectId, requirementId);
        IntegrationConnector connector = jiraConnector();
        String actor = identityService.currentActor();
        // CAP-35 FR-03：人触发写操作个人账号优先（Jira 侧操作人 = 当前用户）
        IntegrationService.WriteIdentity identity =
                integrationService.resolveWriteIdentity(actor, ref.integration());
        String token = identity.secret();
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
        auditService.record("integration", "jira_transition", actor, projectId, true,
                "[#" + ref.integration().getId() + "] " + ref.link().getExternalKey()
                        + " 执行转换「" + target.name() + "」（需求 " + requirementId
                        + "，身份 " + identity.source() + "）");
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
        String actor = identityService.currentActor();
        // CAP-35 FR-03：人触发写操作个人账号优先（Jira worklog 作者 = 当前用户）
        IntegrationService.WriteIdentity identity =
                integrationService.resolveWriteIdentity(actor, ref.integration());
        String token = identity.secret();
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
        auditService.record("integration", "jira_worklog", actor, projectId, true,
                "[#" + ref.integration().getId() + "] " + ref.link().getExternalKey()
                        + " 登记工时 " + seconds + "s（需求 " + requirementId
                        + "，身份 " + identity.source() + "）");
        eventPublisher.publish(SimpleDomainEvent.of("integration.jira.worklogged", projectId,
                null, actor,
                "Jira " + ref.link().getExternalKey() + " 已登记工时 " + formatSeconds(seconds),
                "REQUIREMENT", requirementId, true));
        log.info("Jira issue {} 工时已登记: {}s", ref.link().getExternalKey(), seconds);
        return new JiraWorklogResultView(seconds, remoteStatus);
    }

    /**
     * CAP-101 验收联动：置为解决 + 登记工时（一步编排，验收弹窗默认勾选触发）。
     * 解决转换从动态清单按词表匹配（toStatus 优先、name 兜底），匹配不到 → 跳过转换不报错
     * （issue 可能已在终态或工作流无此转换）；seconds 为空只解决不登记。
     * 部分失败语义：转换失败抛错（远端无变更）；转换成功但工时失败 → transitioned=true +
     * worklogError 返回（不抛）。收尾每步各做一次单条刷新；本地需求 status 绝不动。
     */
    public JiraResolveResultView resolveOnAcceptance(String projectId, String requirementId,
                                                     Long seconds, String comment) {
        if (seconds != null && (seconds <= 0 || seconds > 360_000)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "工时秒数非法（1~360000）: " + seconds);
        }
        Ref ref = resolve(projectId, requirementId);
        IntegrationConnector connector = jiraConnector();
        String actor = identityService.currentActor();
        // CAP-35 FR-03：人触发写操作个人账号优先
        IntegrationService.WriteIdentity identity =
                integrationService.resolveWriteIdentity(actor, ref.integration());
        String token = identity.secret();
        String issueKey = ref.link().getExternalKey();

        IssueTransition target = pickResolveTransition(
                connector.listTransitions(ref.integration(), token, issueKey));
        String remoteStatus = ref.link().getStatus();
        Long loggedSeconds = null;
        String worklogError = null;

        if (target != null) {
            try {
                connector.transitionIssue(ref.integration(), token, issueKey, target.id());
            } catch (Exception e) {
                integrationService.recordCall(ref.integration().getId(), "jira_transition",
                        ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
                throw e;
            }
            integrationService.recordCall(ref.integration().getId(), "jira_transition",
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, true, null);
            remoteStatus = refreshAfterTransit(projectId, requirementId, ref, connector, token);
            log.info("Jira issue {} 验收联动解决: 转换「{}」-> {}", issueKey, target.name(), target.toStatus());
        }
        if (seconds != null) {
            try {
                connector.logWork(ref.integration(), token, issueKey, seconds, comment);
                integrationService.recordCall(ref.integration().getId(), "jira_worklog",
                        ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, true, null);
                loggedSeconds = seconds;
                remoteStatus = refreshAfterTransit(projectId, requirementId, ref, connector, token);
                log.info("Jira issue {} 验收联动工时: {}s", issueKey, seconds);
            } catch (Exception e) {
                // 转换可能已成功——工时不抛，带回错误由前端提示手动补
                integrationService.recordCall(ref.integration().getId(), "jira_worklog",
                        ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId, false, e.getMessage());
                worklogError = e.getMessage();
                log.warn("Jira issue {} 验收联动工时登记失败（转换不回滚）: {}", issueKey, e.getMessage());
            }
        }
        if (target == null && loggedSeconds == null && worklogError == null) {
            // 无可解决转换且不登记工时：纯空转，不审计不广播
            return new JiraResolveResultView(false, null, remoteStatus, null, null);
        }
        String summary = "[" + issueKey + "] "
                + (target != null ? "解决转换「" + target.name() + "」" : "无可用解决转换（跳过）")
                + (loggedSeconds != null ? " + 登记工时 " + formatSeconds(loggedSeconds) : "")
                + (worklogError != null ? "（工时登记失败: " + worklogError + "）" : "");
        auditService.record("integration", "jira_resolve", actor, projectId, worklogError == null,
                summary + "（需求 " + requirementId + "，身份 " + identity.source() + "）");
        eventPublisher.publish(SimpleDomainEvent.of("integration.jira.resolved", projectId,
                null, actor, "Jira " + summary,
                "REQUIREMENT", requirementId, worklogError == null));
        return new JiraResolveResultView(target != null,
                target == null ? null : target.name(), remoteStatus, loggedSeconds, worklogError);
    }

    /**
     * CAP-101 FR-02：从动态转换清单匹配「解决」转换——toStatus 优先、name 兜底，
     * 词表大小写不敏感包含匹配，按 Jira 返回顺序取首个（顺序即工作流推荐序）。
     */
    static IssueTransition pickResolveTransition(List<IssueTransition> available) {
        IssueTransition byName = null;
        for (IssueTransition t : available) {
            if (matchesResolveWord(t.toStatus())) {
                return t;
            }
            if (byName == null && matchesResolveWord(t.name())) {
                byName = t;
            }
        }
        return byName;
    }

    private static final List<String> RESOLVE_WORDS = List.of(
            "解决", "resolved", "resolve", "已完成", "完成", "done", "已关闭", "关闭", "closed", "close");

    static boolean matchesResolveWord(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String w : RESOLVE_WORDS) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
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
