package com.devmind.copilot.service;

import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.plan.JobTerminalProbe;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccess;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolArgs;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.notification.AgentChatPush;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 多步计划（CAP-76 FR-33~FR-35）：{@code submit_plan} 的校验、预览与执行。
 *
 * <p>整份计划 = 一张确认单（参数快照存 {@code copilot_actions.args_json}，不新增表）；
 * 确认后<b>异步</b>在虚拟线程上按序执行（FR-34）、失败即停、已完成步骤不回滚；步骤参数可用
 * {@code ${stepN.field}} 引用前序步骤 {@link ToolResult#data()} 的顶层字段；声明了
 * {@code await} 的步骤会轮询 {@link JobTerminalProbe} 等异步作业终态（FR-35）。
 * 步骤级审计落 {@code copilot_tool_calls}（RUNNING→WAITING→OK/FAILED，FR-36）；
 * 收口时更新确认单终态并推送来源聊天（{@link AgentChatPush}）。</p>
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final String TOOL_NAME = "submit_plan";

    /** 合法占位符：${step<序号>.<字段>}（序号 1 起）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{step(\\d+)\\.([A-Za-z0-9_]+)}");
    /** 任何 ${...} 形态，用于发现写错的占位符并给出明确错误。 */
    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    /** FR-35：可声明 await 的作业类型（探针装配与否在提交期校验，缺一即拒）。 */
    private static final Set<String> AWAIT_KINDS = Set.of("BUILD", "SESSION", "DEPLOYMENT", "TEST_RUN");
    private static final Set<String> AWAIT_KEYS = Set.of("kind", "idField", "timeoutMinutes");

    // ObjectProvider 延迟取：ToolRegistry 收集全部 PlatformTool（含 PlanTool），
    // PlanTool 依赖本服务，直接构造注入 ToolRegistry 会成环。
    private final ObjectProvider<ToolRegistry> registryProvider;
    private final CopilotToolRunner runner;
    private final CopilotToolCallRepository toolCalls;
    private final CopilotActionRepository actions;
    private final CopilotProperties props;
    private final ObjectProvider<ToolAccessPolicy> policies;
    private final PlanProbes probes;
    private final ObjectProvider<AgentChatPush> chatPush;

    public PlanService(ObjectProvider<ToolRegistry> registryProvider, CopilotToolRunner runner,
                       CopilotToolCallRepository toolCalls, CopilotActionRepository actions,
                       CopilotProperties props, ObjectProvider<ToolAccessPolicy> policies,
                       PlanProbes probes, ObjectProvider<AgentChatPush> chatPush) {
        this.registryProvider = registryProvider;
        this.runner = runner;
        this.toolCalls = toolCalls;
        this.actions = actions;
        this.props = props;
        this.policies = policies;
        this.probes = probes;
        this.chatPush = chatPush;
    }

    private ToolRegistry registry() {
        return registryProvider.getObject();
    }

    /** FR-35：步骤的终态等待声明。kind ∈ AWAIT_KINDS；idField = 该步返回 data 里作业 id 的字段名。 */
    public record AwaitSpec(String kind, String idField, int timeoutMinutes) {
    }

    /** 校验通过的一步：seq 从 1 起。 */
    public record PlanStep(int seq, PlatformTool tool, Map<String, Object> args, AwaitSpec await,
                           ToolRisk risk, String projectId) {
    }

    /** 校验通过的计划：risk = 各步最高风险；projectId = 全计划唯一项目（可空）。 */
    public record ValidatedPlan(String goal, List<PlanStep> steps, ToolRisk risk, String projectId) {
    }

    // ------------------------------------------------------------------
    // 校验（提交期回灌模型自纠；执行期同样全量重跑）
    // ------------------------------------------------------------------

    /**
     * 全量校验计划。不合法抛 {@link ToolArgs.InvalidArgumentException}（中文原因，
     * 可直接回灌模型或回执给用户）。
     */
    public ValidatedPlan validate(Map<String, Object> planArgs, String role, String defaultProjectId,
                                  ToolContext ctx) {
        Object rawSteps = planArgs == null ? null : planArgs.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
            throw new ToolArgs.InvalidArgumentException("计划缺少 steps");
        }
        int max = Math.max(2, props.getPlanMaxSteps());
        if (list.size() < 2) {
            throw new ToolArgs.InvalidArgumentException("单步操作请直接调用对应工具，不需要 submit_plan");
        }
        if (list.size() > max) {
            throw new ToolArgs.InvalidArgumentException("计划最多 " + max + " 步，请拆成多份计划");
        }
        String goal = planArgs.get("goal") instanceof String g ? g.trim() : "";

        List<PlanStep> steps = new ArrayList<>();
        Set<String> projectIds = new LinkedHashSet<>();
        ToolRisk aggregated = ToolRisk.WRITE;
        int seq = 0;
        for (Object item : list) {
            seq++;
            if (!(item instanceof Map<?, ?> m)) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步不是对象");
            }
            String toolName = m.get("tool") instanceof String s ? s.trim() : "";
            if (toolName.isEmpty()) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步缺少 tool");
            }
            if (TOOL_NAME.equals(toolName)) {
                throw new ToolArgs.InvalidArgumentException("计划不允许嵌套 submit_plan");
            }
            if (!(m.get("args") instanceof Map<?, ?> rawStepArgs)) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步缺少 args");
            }
            Map<String, Object> stepArgs = new LinkedHashMap<>();
            rawStepArgs.forEach((k, v) -> stepArgs.put(String.valueOf(k), v));

            Optional<PlatformTool> found = registry().find(toolName);
            if (found.isEmpty()) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步工具不存在：" + toolName);
            }
            PlatformTool tool = found.get();
            if (ToolRegistry.rank(role) < ToolRegistry.rank(tool.spec().minRole())) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步工具超出当前角色权限：" + toolName);
            }
            Map<String, Object> validated;
            try {
                validated = ToolArgs.validate(tool.spec().parametersSchema(), stepArgs);
            } catch (ToolArgs.InvalidArgumentException e) {
                throw new ToolArgs.InvalidArgumentException(
                        "第 " + seq + " 步「" + tool.spec().title() + "」参数不合法：" + e.getMessage());
            }
            validatePlaceholders(validated, seq);
            AwaitSpec await = parseAwait(m.get("await"), seq);
            ToolRisk risk = tool.spec().riskFor(validated);
            String projectId = tool.resolveProjectId(validated, ctx);
            if (isBlank(projectId)) {
                projectId = defaultProjectId;
            }
            if (tool.spec().requiresProject() && isBlank(projectId)) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步「" + tool.spec().title()
                        + "」需要项目上下文：请在步骤参数里给出 projectId，或先绑定项目");
            }
            if (!isBlank(projectId)) {
                projectIds.add(projectId);
            }
            if (risk == ToolRisk.DANGEROUS) {
                aggregated = ToolRisk.DANGEROUS;
            }
            steps.add(new PlanStep(seq, tool, validated, await, risk, projectId));
        }
        if (projectIds.size() > 1) {
            throw new ToolArgs.InvalidArgumentException("计划内所有步骤必须在同一个项目（跨项目请拆成多份计划）");
        }
        return new ValidatedPlan(goal, steps, aggregated,
                projectIds.isEmpty() ? null : projectIds.iterator().next());
    }

    /** FR-35：解析并校验步骤的 await 声明；未声明返回 null。 */
    private AwaitSpec parseAwait(Object raw, int seq) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> am)) {
            throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await 应为对象");
        }
        for (Object key : am.keySet()) {
            if (!AWAIT_KEYS.contains(String.valueOf(key))) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await 含未知字段：" + key
                        + "（可选 kind/idField/timeoutMinutes）");
            }
        }
        String kind = am.get("kind") instanceof String s ? s.trim().toUpperCase(Locale.ROOT) : "";
        if (!AWAIT_KINDS.contains(kind)) {
            throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await.kind 不合法"
                    + "（可选 BUILD/SESSION/DEPLOYMENT/TEST_RUN）");
        }
        String idField = am.get("idField") instanceof String s ? s.trim() : "";
        if (idField.isEmpty()) {
            throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await 缺少 idField"
                    + "（该步返回结果里作业 id 的字段名）");
        }
        int timeoutMinutes = 30;
        Object rawTimeout = am.get("timeoutMinutes");
        if (rawTimeout != null) {
            if (!(rawTimeout instanceof Number n)) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await.timeoutMinutes 应为整数分钟");
            }
            timeoutMinutes = n.intValue();
            int maxTimeout = Math.max(1, props.getPlanAwaitMaxTimeoutMinutes());
            if (timeoutMinutes < 1 || timeoutMinutes > maxTimeout) {
                throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await.timeoutMinutes 需在 1~"
                        + maxTimeout + " 分钟");
            }
        }
        if (probes.find(kind).isEmpty()) {
            throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步 await 的作业类型暂不支持等待（" + kind
                    + " 探针未装配），请去掉 await 或改用人工确认");
        }
        return new AwaitSpec(kind, idField, timeoutMinutes);
    }

    /** 占位符合法性：格式必须完整、只能引用排序在前的步骤。 */
    private void validatePlaceholders(Map<String, Object> args, int seq) {
        for (String text : stringsOf(args)) {
            Matcher any = ANY_PLACEHOLDER.matcher(text);
            while (any.find()) {
                Matcher wellFormed = PLACEHOLDER.matcher(any.group());
                if (!wellFormed.matches()) {
                    throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步占位符格式非法：" + any.group()
                            + "（应为 ${step<步骤号>.<字段>}）");
                }
                int ref = Integer.parseInt(wellFormed.group(1));
                if (ref < 1 || ref >= seq) {
                    throw new ToolArgs.InvalidArgumentException("第 " + seq + " 步引用了未执行的步骤：" + any.group()
                            + "（只能引用序号更小的步骤）");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 风险与项目（确认单落库/确认执行时的重算走这里）
    // ------------------------------------------------------------------

    /** 实例入口：计划风险（任一步 DANGEROUS → 整单 DANGEROUS）。 */
    public ToolRisk riskOf(Map<String, Object> planArgs) {
        return riskOf(registry(), planArgs);
    }

    /**
     * 计划风险静态求值（挂 {@code ToolSpec.riskEvaluator}）：只基于 args 与工具注册表。
     * 步骤有任何非法（缺 tool/未知工具/参数不过 schema/嵌套计划）fail-closed 按 DANGEROUS——
     * 提交期 {@link #validate} 已给出具体错误，这里只兜底风险档位。
     */
    public static ToolRisk riskOf(ToolRegistry registry, Map<String, Object> planArgs) {
        Object rawSteps = planArgs == null ? null : planArgs.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
            return ToolRisk.DANGEROUS;
        }
        ToolRisk out = ToolRisk.WRITE;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("tool") instanceof String name)
                    || name.isBlank() || TOOL_NAME.equals(name.trim())) {
                return ToolRisk.DANGEROUS;
            }
            Optional<PlatformTool> found = registry.find(name.trim());
            if (found.isEmpty()) {
                return ToolRisk.DANGEROUS;
            }
            Map<String, Object> stepArgs = m.get("args") instanceof Map<?, ?> a
                    ? castArgs(a) : Map.of();
            try {
                Map<String, Object> validated =
                        ToolArgs.validate(found.get().spec().parametersSchema(), stepArgs);
                if (found.get().spec().riskFor(validated) == ToolRisk.DANGEROUS) {
                    out = ToolRisk.DANGEROUS;
                }
            } catch (RuntimeException e) {
                return ToolRisk.DANGEROUS;
            }
        }
        return out;
    }

    /** 计划归属项目（best-effort）：全计划同项目时返回该项目，否则 null（交给默认项目兜底）。 */
    public String commonProject(Map<String, Object> planArgs, ToolContext ctx) {
        Object rawSteps = planArgs == null ? null : planArgs.get("steps");
        if (!(rawSteps instanceof List<?> list)) {
            return null;
        }
        Set<String> projectIds = new LinkedHashSet<>();
        try {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m) || !(m.get("tool") instanceof String name)) {
                    return null;
                }
                Optional<PlatformTool> found = registry().find(((String) name).trim());
                if (found.isEmpty() || !(m.get("args") instanceof Map<?, ?> a)) {
                    return null;
                }
                Map<String, Object> validated =
                        ToolArgs.validate(found.get().spec().parametersSchema(), castArgs(a));
                String projectId = found.get().resolveProjectId(validated, ctx);
                if (!isBlank(projectId)) {
                    projectIds.add(projectId);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return projectIds.size() == 1 ? projectIds.iterator().next() : null;
    }

    // ------------------------------------------------------------------
    // 预览（确认单 preview_text）
    // ------------------------------------------------------------------

    /** 计划预览：目标 + 编号步骤列表，每步带风险与该工具自己的 preview。 */
    public String preview(Map<String, Object> planArgs, ToolContext ctx) {
        ValidatedPlan plan = validate(planArgs, ctx.role(), ctx.projectId(), ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("计划：").append(plan.goal().isBlank() ? "（未填写目标）" : plan.goal()).append('\n');
        int total = plan.steps().size();
        for (PlanStep step : plan.steps()) {
            sb.append("\n步骤 ").append(step.seq()).append('/').append(total)
                    .append(" [").append(step.risk()).append("] ").append(step.tool().spec().title());
            if (step.await() != null) {
                sb.append("（等待 ").append(step.await().kind()).append(" 终态，超时 ")
                        .append(step.await().timeoutMinutes()).append(" 分钟）");
            }
            sb.append('\n');
            String detail = null;
            try {
                detail = step.tool().preview(
                        new ToolCall("plan_preview_" + step.seq(), step.tool().spec().name(), step.args()), ctx);
            } catch (RuntimeException e) {
                log.warn("计划步骤预览失败: tool={} err={}", step.tool().spec().name(), e.toString());
            }
            if (detail == null || detail.isBlank()) {
                detail = defaultPreview(step);
            }
            sb.append(detail).append('\n');
        }
        return sb.toString().trim();
    }

    private String defaultPreview(PlanStep step) {
        StringBuilder sb = new StringBuilder("参数：");
        step.args().forEach((k, v) -> sb.append('\n').append("- ").append(k).append(": ")
                .append(v instanceof String s ? s : CopilotJson.write(v)));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 异步分发与收口（FR-34）：确认单确认后由对话循环在事务提交后调用
    // ------------------------------------------------------------------

    /**
     * 把已确认的计划单分发到虚拟线程执行。调用方必须保证确认单已处于 EXECUTING 且事务已提交
     * （本方法立即返回，不阻塞确认回执）。
     */
    public void dispatchAsync(String actionId, ToolContext ctx) {
        Thread.ofVirtual().name("copilot-plan-" + actionId).start(() -> runAsync(actionId, ctx));
    }

    private void runAsync(String actionId, ToolContext ctx) {
        long t0 = System.currentTimeMillis();
        ToolResult result;
        Map<String, Object> args = Map.of();
        try {
            CopilotActionEntity action = actions.findById(actionId).orElse(null);
            if (action == null || !CopilotActionEntity.EXECUTING.equals(action.getStatus())) {
                log.warn("计划异步执行跳过：确认单不存在或状态已变化 actionId={}", actionId);
                return;
            }
            args = CopilotJson.readMap(action.getArgsJson());
            result = execute(args, ctx);
        } catch (Exception e) {
            log.error("计划异步执行异常: actionId={} err={}", actionId, e.toString(), e);
            result = ToolResult.fail(ToolResult.UPSTREAM_ERROR,
                    "计划执行异常中断，请到智能体运维页「计划」视图查看步骤审计");
        }
        finalizePlan(actionId, args, ctx, result, System.currentTimeMillis() - t0);
    }

    /**
     * 收口：确认单终态 + 汇总审计行 + 来源聊天推送。收口失败只记日志——
     * sweep（FR-14 分流阈值 {@code plan-execution-timeout-minutes}）兜底把残留 EXECUTING 判 FAILED。
     */
    private void finalizePlan(String actionId, Map<String, Object> args, ToolContext ctx,
                              ToolResult result, long durationMs) {
        boolean ok = result.success();
        try {
            actions.findById(actionId).ifPresent(action -> {
                action.setStatus(ok ? CopilotActionEntity.SUCCEEDED : CopilotActionEntity.FAILED);
                action.setError(ok ? null : CopilotJson.truncate(result.errorMessage(), 2000));
                action.setResultJson(result.sensitive()
                        ? CopilotJson.write(Map.of("sensitive", true,
                                "hash", CopilotJson.sha256(String.valueOf(result.summary()))))
                        : CopilotJson.write(result.data()));
                action.setExecutedAt(Instant.now());
                actions.save(action);
            });
        } catch (Exception e) {
            log.error("计划收口更新确认单失败: actionId={} err={}", actionId, e.toString(), e);
        }
        try {
            CopilotToolCallEntity call = new CopilotToolCallEntity();
            call.setTurnId(ctx.turnId());
            call.setActionId(actionId);
            call.setToolName(TOOL_NAME);
            call.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
            call.setRisk(riskOfSafe(args).name());
            call.setStatus(ok ? CopilotToolCallEntity.OK : CopilotToolCallEntity.FAILED);
            call.setResultSummary(CopilotJson.truncate(CopilotJson.maskText(result.summary()),
                    props.getMaxToolResultChars()));
            call.setError(ok ? null : CopilotJson.truncate(result.errorMessage(), 2000));
            call.setDurationMs(durationMs);
            toolCalls.save(call);
        } catch (Exception e) {
            log.error("计划收口审计落库失败: actionId={} err={}", actionId, e.toString(), e);
        }
        pushPlanResult(args, ctx, result);
    }

    private ToolRisk riskOfSafe(Map<String, Object> args) {
        try {
            return riskOf(args);
        } catch (RuntimeException e) {
            return ToolRisk.DANGEROUS;
        }
    }

    /** FR-34：终态推送（SPI 缺席/来源非聊天入口静默跳过；推送失败不阻断收口）。 */
    private void pushPlanResult(Map<String, Object> args, ToolContext ctx, ToolResult result) {
        if (isBlank(ctx.channel()) || isBlank(ctx.externalChatId())) {
            return;
        }
        AgentChatPush push = chatPush.getIfAvailable();
        if (push == null) {
            return;
        }
        String goal = args.get("goal") instanceof String g && !g.isBlank() ? g : "（未命名计划）";
        Object completed = result.data() == null ? null : result.data().get("completed");
        Object total = result.data() == null ? null : result.data().get("total");
        StringBuilder text = new StringBuilder(result.success() ? "✅" : "❌")
                .append(" 计划《").append(CopilotJson.truncate(goal, 60)).append("》")
                .append(result.success() ? "已完成" : "执行中断");
        if (completed != null && total != null) {
            text.append("（").append(completed).append('/').append(total).append(" 步）");
        }
        if (!result.success() && result.errorMessage() != null) {
            text.append('\n').append(CopilotJson.truncate(result.errorMessage(), 300));
        }
        text.append("\n进度详见平台「智能体运维 → 计划」");
        try {
            push.pushText(ctx.channel(), ctx.externalChatId(), text.toString());
        } catch (Exception e) {
            log.warn("计划终态推送失败: actionId={} err={}", ctx.actionId(), e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 执行（异步线程内按序、失败即停、不回滚；PlanTool.execute 亦走这里）
    // ------------------------------------------------------------------

    public ToolResult execute(Map<String, Object> planArgs, ToolContext ctx) {
        ValidatedPlan plan;
        try {
            plan = validate(planArgs, ctx.role(), ctx.projectId(), ctx);
        } catch (ToolArgs.InvalidArgumentException e) {
            return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "计划已失效：" + e.getMessage());
        }
        Map<Integer, Map<String, Object>> dataByStep = new HashMap<>();
        List<Map<String, Object>> report = new ArrayList<>();
        int total = plan.steps().size();
        for (PlanStep step : plan.steps()) {
            CopilotToolCallEntity row = auditStart(ctx, step);
            long t0 = System.currentTimeMillis();
            // 1) 占位符替换 + 替换后重过 schema（替换值可能超长/变型）
            Map<String, Object> args;
            try {
                args = ToolArgs.validate(step.tool().spec().parametersSchema(),
                        substitute(step.args(), dataByStep, step.seq()));
            } catch (ToolArgs.InvalidArgumentException e) {
                auditEnd(row, CopilotToolCallEntity.FAILED, null, "参数失效：" + e.getMessage(), t0);
                return halt(plan, step, ToolResult.INVALID_ARGUMENTS,
                        "参数失效：" + e.getMessage(), report, ctx);
            }
            auditArgs(row, args);
            // 2) 项目级权限（对象级归属已在 validate 重解析）
            ToolResult denied = ToolAccess.check(policies.getIfAvailable(), ctx.actor(),
                    step.projectId(), step.risk(), step.tool().spec().name());
            if (denied != null) {
                auditEnd(row, CopilotToolCallEntity.REJECTED, null, denied.errorMessage(), t0);
                report.add(stepReport(step, "REJECTED", null, denied.errorMessage()));
                return halt(plan, step, ToolResult.PERMISSION_DENIED, denied.errorMessage(), report, ctx);
            }
            // 3) 执行（SecurityContext 在 runner 内合成）
            ToolContext stepCtx = new ToolContext(ctx.turnId(), ctx.actionId(), ctx.actor(), ctx.role(),
                    ctx.channel(), ctx.externalChatId(), ctx.senderId(), ctx.personalActor(),
                    isBlank(step.projectId()) ? ctx.projectId() : step.projectId(), ctx.projectName(), ctx.now());
            ToolResult result = runner.run(step.tool(),
                    new ToolCall("plan_" + ctx.actionId() + "_" + step.seq(), step.tool().spec().name(), args),
                    stepCtx);
            if (!result.success()) {
                auditEnd(row, CopilotToolCallEntity.FAILED, result.summary(), result.errorMessage(), t0);
                report.add(stepReport(step, "FAILED", null, result.errorMessage()));
                return halt(plan, step, result.errorCode() == null ? ToolResult.UPSTREAM_ERROR : result.errorCode(),
                        result.errorMessage() == null ? "未知原因" : result.errorMessage(), report, ctx);
            }
            // 4) FR-35：声明了 await 的步骤等作业终态（步骤行转 WAITING，轮询探针）
            if (step.await() != null) {
                String jobId = jobIdOf(result, step.await());
                if (jobId == null) {
                    String error = "步骤未产出 " + step.await().idField()
                            + " 字段，无法等待作业终态（请检查工具返回结构）";
                    auditEnd(row, CopilotToolCallEntity.FAILED, result.summary(), error, t0);
                    report.add(stepReport(step, "FAILED", null, error));
                    return halt(plan, step, ToolResult.INVALID_ARGUMENTS, error, report, ctx);
                }
                auditWaiting(row, step.await(), jobId);
                ToolResult awaitFailure = awaitTerminal(step.await(), jobId, ctx);
                if (awaitFailure != null) {
                    auditEnd(row, CopilotToolCallEntity.FAILED, result.summary(),
                            awaitFailure.errorMessage(), t0);
                    report.add(stepReport(step, "FAILED", result.summary(), awaitFailure.errorMessage()));
                    return halt(plan, step, awaitFailure.errorCode(), awaitFailure.errorMessage(), report, ctx);
                }
            }
            auditEnd(row, CopilotToolCallEntity.OK, result.summary(), null, t0);
            dataByStep.put(step.seq(), result.data());
            report.add(stepReport(step, "OK", result.summary(), null));
        }

        StringBuilder text = new StringBuilder("计划已完成（").append(total).append(" 步）");
        if (!plan.goal().isBlank()) {
            text.append("：").append(plan.goal());
        }
        StringBuilder md = new StringBuilder(text).append("\n");
        for (Map<String, Object> row : report) {
            md.append("\n- 步骤 ").append(row.get("seq")).append(' ').append(row.get("title"))
                    .append("：").append(row.get("summary"));
        }
        return ToolResult.ok(CopilotJson.truncate(text.toString(), 2000), md.toString(),
                ToolResult.data("completed", total, "total", total, "steps", report));
    }

    /** FR-35：轮询探针等终态；返回 null = 作业成功，否则为失败/超时回执。 */
    private ToolResult awaitTerminal(AwaitSpec spec, String jobId, ToolContext ctx) {
        JobTerminalProbe probe = probes.find(spec.kind()).orElse(null);
        if (probe == null) {
            return ToolResult.fail(ToolResult.TOOL_UNAVAILABLE, "作业探针已下线：" + spec.kind());
        }
        long deadline = nowMillis() + spec.timeoutMinutes() * 60_000L;
        long pollMs = Math.max(5, props.getPlanAwaitPollSeconds()) * 1000L;
        while (true) {
            JobState state;
            try {
                state = probe.probe(jobId, ctx.actor());
            } catch (Exception e) {
                log.warn("作业终态探测异常，按 UNKNOWN 续等: kind={} id={} err={}", spec.kind(), jobId, e.toString());
                state = JobState.UNKNOWN;
            }
            if (state == JobState.SUCCEEDED) {
                return null;
            }
            if (state == JobState.FAILED) {
                String summary = null;
                try {
                    summary = probe.failureSummary(jobId);
                } catch (Exception e) {
                    log.warn("作业失败摘要读取失败: kind={} id={} err={}", spec.kind(), jobId, e.toString());
                }
                return ToolResult.fail(ToolResult.UPSTREAM_ERROR, spec.kind() + " 作业 " + jobId + " 失败"
                        + (summary == null || summary.isBlank() ? "" : "：" + CopilotJson.truncate(summary, 300)));
            }
            if (nowMillis() >= deadline) {
                return ToolResult.fail(ToolResult.TIMEOUT, "等待 " + spec.kind() + " 作业 " + jobId
                        + " 终态超时（" + spec.timeoutMinutes() + " 分钟），请到平台核实作业状态，勿直接重试");
            }
            try {
                sleepAwait(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.fail(ToolResult.TIMEOUT, "等待 " + spec.kind() + " " + jobId
                        + " 终态被中断，请到平台核实作业状态，勿直接重试");
            }
        }
    }

    /** 测试钩子：等待时钟（单测覆写以虚拟时间驱动超时路径）。 */
    long nowMillis() {
        return System.currentTimeMillis();
    }

    /** 测试钩子：轮询间隔睡眠（单测覆写避免真实等待）。 */
    void sleepAwait(long pollMs) throws InterruptedException {
        Thread.sleep(pollMs);
    }

    /** await 作业 id 取值：Number → 长整数字符串；非空字符串直接用；其余视为缺失。 */
    private String jobIdOf(ToolResult result, AwaitSpec spec) {
        Object value = result.data() == null ? null : result.data().get(spec.idField());
        if (value instanceof Number n) {
            return Long.toString(n.longValue());
        }
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** 中断收口：剩余步骤补 REJECTED 审计，回执写明已完成/失败点/未执行。 */
    private ToolResult halt(ValidatedPlan plan, PlanStep failed, String errorCode, String error,
                            List<Map<String, Object>> report, ToolContext ctx) {
        int total = plan.steps().size();
        for (PlanStep step : plan.steps()) {
            if (step.seq() > failed.seq()) {
                auditStep(ctx, step, step.args(), CopilotToolCallEntity.REJECTED, null,
                        "前序步骤失败，未执行", 0);
                report.add(stepReport(step, "REJECTED", null, "前序步骤失败，未执行"));
            }
        }
        int done = failed.seq() - 1;
        String message = "计划执行中断：已完成 " + done + "/" + total + " 步。第 " + failed.seq() + " 步「"
                + failed.tool().spec().title() + "」失败：" + error + "。剩余 " + (total - failed.seq())
                + " 步未执行，已完成的步骤不回滚。";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("completed", done);
        data.put("total", total);
        data.put("failedStep", failed.seq());
        data.put("steps", report);
        return new ToolResult(false, errorCode, message, message, message, data, false);
    }

    private Map<String, Object> stepReport(PlanStep step, String status, String summary, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", step.seq());
        row.put("tool", step.tool().spec().name());
        row.put("title", step.tool().spec().title());
        row.put("status", status);
        row.put("summary", summary == null ? "" : CopilotJson.truncate(summary, 500));
        row.put("error", error == null ? "" : CopilotJson.truncate(error, 500));
        return row;
    }

    // ------------------------------------------------------------------
    // 步骤审计生命周期（FR-36）：RUNNING 起步 → 替换后参数回写 → WAITING（await）→ OK/FAILED
    // ------------------------------------------------------------------

    private CopilotToolCallEntity auditStart(ToolContext ctx, PlanStep step) {
        try {
            CopilotToolCallEntity row = new CopilotToolCallEntity();
            row.setTurnId(ctx.turnId());
            row.setActionId(ctx.actionId());
            row.setStepSeq(step.seq());
            row.setToolName(step.tool().spec().name());
            row.setArgsJson(CopilotJson.maskedArgsJson(step.args(), props.getMaxToolResultChars()));
            row.setRisk(step.risk().name());
            row.setStatus(CopilotToolCallEntity.RUNNING);
            return toolCalls.save(row);
        } catch (Exception e) {
            log.warn("计划步骤审计起步失败: turn={} step={} err={}", ctx.turnId(), step.seq(), e.toString());
            return null;
        }
    }

    /** 占位符替换后的实际参数回写审计行（起步时写的是替换前快照）。 */
    private void auditArgs(CopilotToolCallEntity row, Map<String, Object> args) {
        if (row == null) {
            return;
        }
        try {
            row.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
            toolCalls.save(row);
        } catch (Exception e) {
            log.warn("计划步骤参数审计回写失败: step={} err={}", row.getStepSeq(), e.toString());
        }
    }

    private void auditWaiting(CopilotToolCallEntity row, AwaitSpec spec, String jobId) {
        if (row == null) {
            return;
        }
        try {
            row.setStatus(CopilotToolCallEntity.WAITING);
            row.setResultSummary("等待 " + spec.kind() + " 作业 " + jobId + " 终态（超时 "
                    + spec.timeoutMinutes() + " 分钟）");
            toolCalls.save(row);
        } catch (Exception e) {
            log.warn("计划步骤等待标记失败: step={} err={}", row.getStepSeq(), e.toString());
        }
    }

    private void auditEnd(CopilotToolCallEntity row, String status, String summary, String error, long t0) {
        if (row == null) {
            return;
        }
        try {
            row.setStatus(status);
            row.setResultSummary(CopilotJson.truncate(CopilotJson.maskText(summary),
                    props.getMaxToolResultChars()));
            row.setError(CopilotJson.truncate(error, 2000));
            row.setDurationMs(System.currentTimeMillis() - t0);
            toolCalls.save(row);
        } catch (Exception e) {
            log.warn("计划步骤审计收口失败: step={} err={}", row.getStepSeq(), e.toString());
        }
    }

    /** 一次性写步骤审计行（中断补 REJECTED 等无生命周期的场景）。 */
    private void auditStep(ToolContext ctx, PlanStep step, Map<String, Object> args, String status,
                           String summary, String error, long durationMs) {
        try {
            CopilotToolCallEntity row = new CopilotToolCallEntity();
            row.setTurnId(ctx.turnId());
            row.setActionId(ctx.actionId());
            row.setStepSeq(step.seq());
            row.setToolName(step.tool().spec().name());
            row.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
            row.setRisk(step.risk().name());
            row.setStatus(status);
            row.setResultSummary(CopilotJson.truncate(CopilotJson.maskText(summary),
                    props.getMaxToolResultChars()));
            row.setError(CopilotJson.truncate(error, 2000));
            row.setDurationMs(durationMs);
            toolCalls.save(row);
        } catch (Exception e) {
            log.warn("计划步骤审计落库失败: turn={} step={} err={}", ctx.turnId(), step.seq(), e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 占位符替换
    // ------------------------------------------------------------------

    /**
     * 把步骤参数里的 ${stepN.field} 替换为前序步骤 data 的顶层字段。
     * 整串恰好是占位符时保留原值类型；嵌入更长字符串时按文本替换。字段缺失即失败。
     */
    private Map<String, Object> substitute(Map<String, Object> args,
                                           Map<Integer, Map<String, Object>> dataByStep, int seq) {
        Map<String, Object> out = new LinkedHashMap<>();
        args.forEach((k, v) -> out.put(k, substituteValue(v, dataByStep, seq)));
        return out;
    }

    private Object substituteValue(Object value, Map<Integer, Map<String, Object>> dataByStep, int seq) {
        if (value instanceof String s) {
            return substituteString(s, dataByStep, seq);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substituteValue(item, dataByStep, seq));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), substituteValue(v, dataByStep, seq)));
            return out;
        }
        return value;
    }

    private Object substituteString(String text, Map<Integer, Map<String, Object>> dataByStep, int seq) {
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            return placeholderValue(whole, dataByStep, seq);
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object replacement = placeholderValue(matcher, dataByStep, seq);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(replacement)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object placeholderValue(Matcher matcher, Map<Integer, Map<String, Object>> dataByStep, int seq) {
        int ref = Integer.parseInt(matcher.group(1));
        String field = matcher.group(2);
        Map<String, Object> data = dataByStep.get(ref);
        Object value = data == null ? null : data.get(field);
        if (value == null) {
            throw new ToolArgs.InvalidArgumentException("第 " + ref + " 步未产出字段 " + field
                    + "（第 " + seq + " 步引用了 ${step" + ref + "." + field + "}）");
        }
        return value;
    }

    private static List<String> stringsOf(Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        collectStrings(args, out);
        return out;
    }

    private static void collectStrings(Object value, List<String> out) {
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectStrings(item, out));
        } else if (value instanceof Map<?, ?> m) {
            m.values().forEach(v -> collectStrings(v, out));
        }
    }

    private static Map<String, Object> castArgs(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
