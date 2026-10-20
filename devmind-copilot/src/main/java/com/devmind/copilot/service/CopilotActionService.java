package com.devmind.copilot.service;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccess;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolArgs;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写操作确认单（CAP-76 FR-13~FR-16）：创建、原子抢占执行、取消与过期清扫。
 *
 * <p>执行路径每次都重新解析 actor/角色、参数 schema、目标对象所属项目与项目级权限；
 * 不使用回调 payload 里的参数，只使用服务端 {@code args_json}。</p>
 */
@Service
public class CopilotActionService {

    /** 当前操作者（重新解析后的值，不是下单快照）。 */
    public record Principal(String actor, String role, String personalActor, boolean identitySupported) {
    }

    public record Outcome(boolean success, String message, CopilotActionEntity action, ToolResult result) {
    }

    private final CopilotActionRepository actions;
    private final CopilotToolCallRepository toolCalls;
    private final ToolRegistry registry;
    private final CopilotToolRunner runner;
    private final CopilotProperties props;
    private final ObjectProvider<ToolAccessPolicy> policies;

    public CopilotActionService(CopilotActionRepository actions,
                                CopilotToolCallRepository toolCalls,
                                ToolRegistry registry,
                                CopilotToolRunner runner,
                                CopilotProperties props,
                                ObjectProvider<ToolAccessPolicy> policies) {
        this.actions = actions;
        this.toolCalls = toolCalls;
        this.registry = registry;
        this.runner = runner;
        this.props = props;
        this.policies = policies;
    }

    /** 默认预览（工具未覆写 preview 时使用）：展示工具名与规范化参数；敏感键掩码。 */
    public String previewOf(PlatformTool tool, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("操作：").append(tool.spec().title()).append('\n');
        sb.append("参数：\n");
        for (Map.Entry<String, Object> e : args.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(display(e.getValue())).append('\n');
        }
        return sb.toString().trim();
    }

    private String display(Object value) {
        if (value == null) {
            return "（空）";
        }
        String text = value instanceof String s ? s : CopilotJson.write(value);
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "…";
    }

    /** 生成唯一短码（同 scope 下 PENDING 不重复）。 */
    public String newUniqueShortCode(String channel, String externalChatId, String senderId) {
        for (int i = 0; i < 8; i++) {
            String code = CopilotIds.newShortCode();
            boolean used = actions
                    .findFirstByChannelAndExternalChatIdAndSenderIdAndShortCodeAndStatus(
                            channel, externalChatId, senderId, code, CopilotActionEntity.PENDING)
                    .isPresent();
            if (!used) {
                return code;
            }
        }
        return CopilotIds.newShortCode();
    }

    @Transactional
    public CopilotActionEntity create(String turnId, String channel, String externalChatId, String senderId,
                                      String actor, String projectId, String toolName, Map<String, Object> args,
                                      ToolRisk risk, String shortCode, String previewText) {
        CopilotActionEntity action = new CopilotActionEntity();
        action.setId(CopilotIds.newId());
        action.setTurnId(turnId);
        action.setChannel(channel);
        action.setExternalChatId(externalChatId);
        action.setSenderId(senderId);
        action.setActor(actor);
        action.setProjectId(projectId);
        action.setToolName(toolName);
        action.setArgsJson(CopilotJson.write(args));
        action.setRisk(risk.name());
        action.setPreviewText(previewText);
        action.setStatus(CopilotActionEntity.PENDING);
        action.setShortCode(shortCode);
        action.setCreatedAt(Instant.now());
        action.setExpiresAt(Instant.now().plus(props.getConfirmTtlMinutes(), ChronoUnit.MINUTES));
        actions.save(action);

        CopilotToolCallEntity call = new CopilotToolCallEntity();
        call.setTurnId(turnId);
        call.setActionId(action.getId());
        call.setToolName(toolName);
        call.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
        call.setRisk(risk.name());
        call.setStatus(CopilotToolCallEntity.PENDING_CONFIRM);
        call.setResultSummary("待确认，短码 " + shortCode);
        toolCalls.save(call);
        return action;
    }

    /**
     * 执行确认单（FR-14/FR-15）。{@code cardPath=true} 时 DANGEROUS 一律拒绝。
     *
     * @param triggerTurnId 确认路径新开的 turn id（工具调用审计挂这里）
     */
    @Transactional
    public Outcome execute(String actionId, Principal principal, ToolContext ctx, String triggerTurnId,
                           boolean cardPath) {
        Instant now = Instant.now();
        int claimed = actions.claim(actionId, now);
        CopilotActionEntity action = actions.findById(actionId)
                .orElseThrow(() -> new IllegalStateException("确认单不存在"));
        if (claimed == 0) {
            String message = switch (action.getStatus()) {
                case CopilotActionEntity.EXPIRED -> "确认单已过期，请重新发起";
                case CopilotActionEntity.CANCELLED -> "确认单已取消";
                case CopilotActionEntity.SUCCEEDED -> "确认单已执行";
                case CopilotActionEntity.FAILED -> "确认单执行失败，请重新发起";
                case CopilotActionEntity.EXECUTING -> "确认单正在执行或已执行，请勿重复确认";
                default -> action.getExpiresAt().isBefore(now)
                        ? "确认单已过期，请重新发起" : "确认单状态已变化，请刷新";
            };
            return new Outcome(false, message, action, null);
        }

        // 1) 操作者身份再校验
        if (!action.getSenderId().equals(ctx.senderId())) {
            return fail(action, triggerTurnId, "只有发起人可以确认该操作");
        }
        if (action.getActor() != null && !action.getActor().equals(principal.actor())) {
            return fail(action, triggerTurnId, "身份已变更，请重新发起");
        }
        // 2) 个人绑定门禁（FR-19）
        if (!writeAllowed(principal)) {
            return fail(action, triggerTurnId, "请先 /bindme 绑定个人身份后再执行写操作");
        }
        // 3) 工具与角色
        Optional<PlatformTool> found = registry.find(action.getToolName());
        if (found.isEmpty()) {
            return fail(action, triggerTurnId, "工具已下线，无法执行");
        }
        PlatformTool tool = found.get();
        if (ToolRegistry.rank(principal.role()) < ToolRegistry.rank(tool.spec().minRole())) {
            return fail(action, triggerTurnId, "当前角色无权执行该操作");
        }
        // 4) 参数 schema 与动态风险
        Map<String, Object> args;
        ToolRisk risk;
        try {
            args = ToolArgs.validate(tool.spec().parametersSchema(), CopilotJson.readMap(action.getArgsJson()));
            risk = tool.spec().riskFor(args);
        } catch (ToolArgs.InvalidArgumentException e) {
            return fail(action, triggerTurnId, "参数已失效：" + e.getMessage());
        }
        // 5) 卡片路径不得执行危险操作（渲染层约束不是授权边界）
        if (cardPath && risk == ToolRisk.DANGEROUS) {
            return fail(action, triggerTurnId, "危险操作请回复文本短码确认");
        }
        // 6) 重新解析对象所属项目 + 项目级权限
        String projectId = tool.resolveProjectId(args, ctx);
        if (projectId == null || projectId.isBlank()) {
            projectId = action.getProjectId();
        }
        ToolResult denied = ToolAccess.check(policies.getIfAvailable(), principal.actor(),
                projectId, risk, tool.spec().name());
        if (denied != null) {
            return fail(action, triggerTurnId, denied.errorMessage());
        }
        if (tool.spec().requiresProject() && (projectId == null || projectId.isBlank())) {
            return fail(action, triggerTurnId, "该操作需要项目上下文，请重新发起并指定项目");
        }
        if (projectId != null && !projectId.equals(action.getProjectId())) {
            action.setProjectId(projectId);
        }

        // 7) 计划单分流（M8 FR-34）：确认校验全部通过后不在确认请求里同步跑计划——
        // 确认单保持 EXECUTING，由对话循环在事务提交后 dispatchAsync 到虚拟线程；
        // 终态由 PlanService 收口（SUCCEEDED/FAILED + 汇总审计 + 聊天推送）。
        if (PlanService.TOOL_NAME.equals(tool.spec().name())) {
            toolCalls.closePendingByActionId(action.getId(), CopilotToolCallEntity.CONFIRMED);
            int total = args.get("steps") instanceof List<?> steps ? steps.size() : 0;
            return new Outcome(true, "计划已开始执行（共 " + total + " 步），完成后会推送结果；"
                    + "进度见平台「智能体运维 → 计划」", action, null);
        }

        // 8) 执行（服务端参数快照；SecurityContext 在 runner 内合成）
        long t0 = System.currentTimeMillis();
        ToolResult result;
        try {
            result = runner.run(tool, new ToolCall("confirm_" + action.getId(), tool.spec().name(), args), ctx);
        } catch (RuntimeException e) {
            result = ToolResult.fail(ToolResult.UPSTREAM_ERROR, "工具执行失败，请稍后再试");
        }
        long duration = System.currentTimeMillis() - t0;
        boolean ok = result.success();
        action.setStatus(ok ? CopilotActionEntity.SUCCEEDED : CopilotActionEntity.FAILED);
        action.setError(ok ? null : cut(result.errorMessage(), 2000));
        action.setResultJson(result.sensitive()
                ? CopilotJson.write(Map.of("sensitive", true,
                        "hash", CopilotJson.sha256(String.valueOf(result.summary()))))
                : CopilotJson.write(result.data()));
        action.setExecutedAt(Instant.now());
        actions.save(action);

        // 8) 审计链：原 PENDING_CONFIRM → CONFIRMED；新确认 turn 下写执行记录
        toolCalls.closePendingByActionId(action.getId(), CopilotToolCallEntity.CONFIRMED);
        CopilotToolCallEntity call = new CopilotToolCallEntity();
        call.setTurnId(triggerTurnId);
        call.setActionId(action.getId());
        call.setToolName(action.getToolName());
        call.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
        call.setRisk(risk.name());
        call.setStatus(ok ? CopilotToolCallEntity.OK : CopilotToolCallEntity.FAILED);
        call.setResultSummary(result.sensitive()
                ? "敏感结果 sha256=" + CopilotJson.sha256(String.valueOf(result.summary()))
                : cut(result.summary(), props.getMaxToolResultChars()));
        call.setError(ok ? null : cut(result.errorMessage(), 2000));
        call.setDurationMs(duration);
        toolCalls.save(call);

        String receipt = ok
                ? (result.displayMarkdown() != null && !result.displayMarkdown().isBlank()
                        ? result.displayMarkdown() : "已执行：" + action.getToolName())
                : "执行失败：" + (result.errorMessage() == null ? "未知原因" : result.errorMessage());
        return new Outcome(ok, receipt, action, result);
    }

    private Outcome fail(CopilotActionEntity action, String triggerTurnId, String message) {
        action.setStatus(CopilotActionEntity.FAILED);
        action.setError(cut(message, 2000));
        action.setExecutedAt(Instant.now());
        actions.save(action);
        toolCalls.closePendingByActionId(action.getId(), CopilotToolCallEntity.CANCELLED);
        CopilotToolCallEntity call = new CopilotToolCallEntity();
        call.setTurnId(triggerTurnId);
        call.setActionId(action.getId());
        call.setToolName(action.getToolName());
        call.setArgsJson("{}");
        call.setRisk(action.getRisk());
        call.setStatus(CopilotToolCallEntity.FAILED);
        call.setError(cut(message, 2000));
        toolCalls.save(call);
        return new Outcome(false, message, action, null);
    }

    public boolean writeAllowed(Principal principal) {
        if (principal.personalActor() != null && !principal.personalActor().isBlank()) {
            return true;
        }
        return !principal.identitySupported() && props.isAllowDefaultActorWrites();
    }

    /** 取消 PENDING（FR-16）；返回 null = 成功，否则为原因。 */
    @Transactional
    public String cancel(String actionId) {
        Instant now = Instant.now();
        int updated = actions.cancelPending(actionId, now, "已取消");
        if (updated > 0) {
            toolCalls.closePendingByActionId(actionId, CopilotToolCallEntity.CANCELLED);
            return null;
        }
        return actions.findById(actionId).map(a -> switch (a.getStatus()) {
            case CopilotActionEntity.SUCCEEDED, CopilotActionEntity.EXECUTING -> "该确认单已执行，无法取消";
            case CopilotActionEntity.EXPIRED -> "该确认单已过期";
            case CopilotActionEntity.CANCELLED -> "该确认单已取消";
            default -> "该确认单无法取消";
        }).orElse("确认单不存在");
    }

    public Optional<CopilotActionEntity> findInScope(String channel, String externalChatId, String senderId,
                                                     String shortCode) {
        return actions.findFirstByChannelAndExternalChatIdAndSenderIdAndShortCodeAndStatus(
                channel, externalChatId, senderId, normalizeCode(shortCode), CopilotActionEntity.PENDING);
    }

    public List<CopilotActionEntity> pendingInScope(String channel, String externalChatId, String senderId) {
        return actions.findByChannelAndExternalChatIdAndSenderIdAndStatusOrderByCreatedAtAsc(
                channel, externalChatId, senderId, CopilotActionEntity.PENDING);
    }

    /** 完整短码判定：A7K2 是前缀而非可确认短码（FR-10/FR-17）。 */
    public boolean isPrefixOnly(String channel, String externalChatId, String senderId, String code) {
        String normalized = normalizeCode(code);
        if (normalized.contains("-")) {
            return false;
        }
        return !actions
                .findByChannelAndExternalChatIdAndSenderIdAndStatusAndShortCodeStartingWithOrderByShortCodeAsc(
                        channel, externalChatId, senderId, CopilotActionEntity.PENDING, normalized + "-")
                .isEmpty();
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /** 分钟级清扫：PENDING→EXPIRED、EXECUTING→FAILED（FR-14/FR-29）；计划单分流到更长阈值（FR-34）。 */
    @Transactional
    public int sweep() {
        Instant now = Instant.now();
        int expired = actions.expirePending(now);
        int interrupted = actions.failInterrupted(
                now.minusSeconds(props.getTurnTimeoutSeconds()), "执行中断", PlanService.TOOL_NAME);
        int interruptedPlans = actions.failInterruptedPlans(
                now.minus(props.getPlanExecutionTimeoutMinutes(), ChronoUnit.MINUTES), "执行中断",
                PlanService.TOOL_NAME);
        toolCalls.closeExpiredActions();
        toolCalls.failStaleSteps(now.minus(props.getPlanExecutionTimeoutMinutes(), ChronoUnit.MINUTES));
        return expired + interrupted + interruptedPlans;
    }

    public List<CopilotActionEntity> allPendingForDisplay(String channel, String externalChatId, String senderId) {
        List<CopilotActionEntity> raw = pendingInScope(channel, externalChatId, senderId);
        List<CopilotActionEntity> valid = new ArrayList<>();
        Instant now = Instant.now();
        for (CopilotActionEntity a : raw) {
            if (a.getExpiresAt().isAfter(now)) {
                valid.add(a);
            }
        }
        return valid;
    }

    private String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
