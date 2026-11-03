package com.devmind.auth.authz;

import com.devmind.auth.menu.MenuPermissionService;
import com.devmind.common.authz.AuthorizationAuditEvent;
import com.devmind.common.authz.AuthorizationAuditSink;
import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.AuthorizationException;
import com.devmind.common.authz.AuthorizationService;
import com.devmind.common.authz.ConfirmationVerifier;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationEffect;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.ResourcePolicy;
import com.devmind.common.authz.ResourceTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * CAP-108 FR-02/04/07 核心授权引擎（§4.2.3 判定链）：
 * 有效主体 → 操作已登记且策略就绪 → 基础角色 → ACTION 表达式 → 确认凭据（AI 写命令）→ 资源策略。
 * 内置 ADMIN/SYSTEM 绕过基础角色与 ACTION（平台运维语义），资源策略仍执行（OWNER_ONLY 不因 ADMIN 扩展）；
 * 缺身份/缺策略/服务异常一律拒绝或 ERROR，不回退 local/仅 RBAC。每次判定写审计；
 * 审计写入失败时，ALLOW 的写/危险操作降级为 ERROR（不派发副作用）。
 */
@Service
public class CoreAuthorizationService implements AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CoreAuthorizationService.class);

    private final OperationRegistry registry;
    private final SubjectResolver subjects;
    private final MenuPermissionService menus;
    private final ObjectProvider<ConfirmationVerifier> verifier;
    private final ObjectProvider<AuthorizationAuditSink> auditSink;

    private record Verdict(ResolvedSubject subject, OperationSpec spec, AuthorizationDecision decision) {
    }

    public CoreAuthorizationService(OperationRegistry registry,
                                    SubjectResolver subjects,
                                    MenuPermissionService menus,
                                    ObjectProvider<ConfirmationVerifier> verifier,
                                    ObjectProvider<AuthorizationAuditSink> auditSink) {
        this.registry = registry;
        this.subjects = subjects;
        this.menus = menus;
        this.verifier = verifier;
        this.auditSink = auditSink;
    }

    @Override
    public void require(String operationId, ResourceTarget target) {
        ExecutionContext ctx = ExecutionContextHolder.current().orElse(null);
        AuthorizationDecision decision = decideChecked(operationId, target, ctx);
        if (!decision.allowed()) {
            throw AuthorizationException.from(decision, operationId);
        }
    }

    @Override
    public AuthorizationDecision check(String operationId, ResourceTarget target) {
        ExecutionContext ctx = ExecutionContextHolder.current().orElse(null);
        return decideChecked(operationId, target, ctx);
    }

    /** 判定 + 审计；审计失败的写/危险 ALLOW 降级为 ERROR。 */
    private AuthorizationDecision decideChecked(String operationId, ResourceTarget target, ExecutionContext ctx) {
        Verdict verdict = decide(operationId, target, ctx);
        boolean audited = audit(operationId, target, ctx, verdict);
        AuthorizationDecision decision = verdict.decision();
        if (!audited && decision.allowed() && verdict.spec() != null
                && (verdict.spec().effect() == OperationEffect.WRITE
                || verdict.spec().effect() == OperationEffect.DANGEROUS)) {
            return AuthorizationDecision.error(ReasonCodes.POLICY_UNAVAILABLE, "授权审计不可用");
        }
        return decision;
    }

    private Verdict decide(String operationId, ResourceTarget target, ExecutionContext ctx) {
        OperationSpec spec = registry.find(operationId);
        if (spec == null) {
            return new Verdict(null, null, AuthorizationDecision.error(
                    ReasonCodes.OPERATION_UNKNOWN, "操作未登记: " + operationId));
        }
        if (ctx == null) {
            return new Verdict(null, spec, AuthorizationDecision.deny(
                    ReasonCodes.SUBJECT_UNKNOWN, "缺少可信执行上下文"));
        }
        SubjectResolver.Resolution resolution = subjects.resolve(ctx);
        if (!resolution.resolved()) {
            return new Verdict(null, spec, AuthorizationDecision.deny(
                    resolution.denyReason(), resolution.message()));
        }
        ResolvedSubject subject = resolution.subject();

        boolean privileged = subject.admin() || subject.system();
        if (!privileged) {
            if (!spec.allowedSubjectTypes().contains(subject.subjectType())) {
                return new Verdict(subject, spec, AuthorizationDecision.deny(ReasonCodes.SUBJECT_TYPE_DENIED,
                        "主体类型不被该操作接纳: " + subject.subjectType()));
            }
            if (!spec.allowedBaseRoles().contains(subject.baseRole())) {
                return new Verdict(subject, spec, AuthorizationDecision.deny(ReasonCodes.ROLE_DENIED,
                        "基础角色不满足: " + subject.baseRole()));
            }
            if (!grantSatisfied(spec.grantRule(), subject.roleCode())) {
                return new Verdict(subject, spec, AuthorizationDecision.deny(ReasonCodes.ACTION_DENIED,
                        "缺少操作授权: " + spec.grantRule().keys()));
            }
        }

        // AI 来源写命令：校验服务端确认凭据（FR-06；GENERATE/READ 不需要，人工 WEB 不需要）
        if (ctx.aiSourced() && (spec.effect() == OperationEffect.WRITE
                || spec.effect() == OperationEffect.DANGEROUS)) {
            ConfirmationVerifier v = verifier.getIfAvailable();
            if (v == null) {
                return new Verdict(subject, spec, AuthorizationDecision.error(
                        ReasonCodes.CONFIRMATION_REQUIRED, "确认凭据校验不可用"));
            }
            String denial;
            try {
                denial = v.verify(ctx, spec, target);
            } catch (RuntimeException e) {
                log.error("确认凭据校验异常: op={} actionId={}", operationId, ctx.actionId(), e);
                return new Verdict(subject, spec, AuthorizationDecision.error(
                        ReasonCodes.CONFIRMATION_REQUIRED, "确认凭据校验异常"));
            }
            if (denial != null) {
                return new Verdict(subject, spec, AuthorizationDecision.deny(
                        ReasonCodes.CONFIRMATION_REQUIRED, denial));
            }
        }

        if (spec.resourcePolicyId() != null) {
            ResourcePolicy policy = registry.policy(spec.resourcePolicyId());
            if (policy == null) {
                return new Verdict(subject, spec, AuthorizationDecision.error(ReasonCodes.POLICY_UNAVAILABLE,
                        "资源策略未就绪: " + spec.resourcePolicyId()));
            }
            try {
                AuthorizationDecision d = policy.evaluate(subject, spec,
                        target == null ? ResourceTarget.none() : target);
                if (d == null) {
                    return new Verdict(subject, spec, AuthorizationDecision.error(ReasonCodes.POLICY_UNAVAILABLE,
                            "资源策略返回空: " + spec.resourcePolicyId()));
                }
                if (!d.allowed()) {
                    return new Verdict(subject, spec, d);
                }
            } catch (RuntimeException e) {
                log.error("资源策略异常: op={} policy={}", operationId, spec.resourcePolicyId(), e);
                return new Verdict(subject, spec, AuthorizationDecision.error(ReasonCodes.POLICY_UNAVAILABLE,
                        "资源策略异常: " + spec.resourcePolicyId()));
            }
        }
        return new Verdict(subject, spec, AuthorizationDecision.allow());
    }

    private boolean grantSatisfied(GrantRule rule, String roleCode) {
        return switch (rule.kind()) {
            case NONE -> true;
            case ANY_OF -> menus.hasAnyGrant(roleCode, rule.keys());
            case ALL_OF -> menus.hasAllGrants(roleCode, rule.keys());
        };
    }

    /** 拒绝记录不随业务回滚丢失（sink 独立事务提交）；失败返回 false。 */
    private boolean audit(String operationId, ResourceTarget target, ExecutionContext ctx, Verdict verdict) {
        AuthorizationAuditSink sink = auditSink.getIfAvailable();
        if (sink == null) {
            log.warn("CAP-108 授权审计 sink 缺失: op={} decision={}", operationId,
                    verdict.decision().outcome());
            return false;
        }
        AuthorizationDecision decision = verdict.decision();
        ResolvedSubject subject = verdict.subject();
        OperationSpec spec = verdict.spec();
        try {
            sink.record(new AuthorizationAuditEvent(
                    Instant.now(),
                    ctx == null ? null : ctx.traceId(),
                    ctx == null ? null : ctx.commandId(),
                    ctx == null ? null : ctx.subjectType().name(),
                    ctx == null ? null : ctx.subjectId(),
                    ctx == null ? null : ctx.username(),
                    subject == null ? null : subject.roleCode(),
                    ctx == null ? null : ctx.clientId(),
                    ctx == null ? null : ctx.credentialId(),
                    ctx == null ? null : ctx.source().name(),
                    operationId,
                    spec == null ? null : spec.contractVersion(),
                    target == null ? null : target.targetType(),
                    target == null ? null : target.id(),
                    target == null ? null : target.projectId(),
                    decision.outcome().name(),
                    decision.reasonCode(),
                    null,
                    ctx == null ? null : ctx.actionId(),
                    ctx == null ? null : ctx.operationKey()));
            return true;
        } catch (RuntimeException e) {
            log.error("CAP-108 授权审计写入失败: op={} decision={}", operationId, decision.outcome(), e);
            return false;
        }
    }
}
