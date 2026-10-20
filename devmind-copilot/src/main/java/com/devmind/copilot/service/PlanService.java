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
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * 多步计划（CAP-76 FR-33，M7）：{@code submit_plan} 的校验、预览与执行。
 *
 * <p>整份计划 = 一张确认单（参数快照存 {@code copilot_actions.args_json}，不新增表）；
 * 确认后按序执行、失败即停、已完成步骤不回滚；步骤参数可用 {@code ${stepN.field}} 引用
 * 前序步骤 {@link ToolResult#data()} 的顶层字段。步骤级审计落 {@code copilot_tool_calls}。</p>
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final String TOOL_NAME = "submit_plan";

    /** 合法占位符：${step<序号>.<字段>}（序号 1 起）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{step(\\d+)\\.([A-Za-z0-9_]+)}");
    /** 任何 ${...} 形态，用于发现写错的占位符并给出明确错误。 */
    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    // ObjectProvider 延迟取：ToolRegistry 收集全部 PlatformTool（含 PlanTool），
    // PlanTool 依赖本服务，直接构造注入 ToolRegistry 会成环。
    private final ObjectProvider<ToolRegistry> registryProvider;
    private final CopilotToolRunner runner;
    private final CopilotToolCallRepository toolCalls;
    private final CopilotProperties props;
    private final ObjectProvider<ToolAccessPolicy> policies;

    public PlanService(ObjectProvider<ToolRegistry> registryProvider, CopilotToolRunner runner,
                       CopilotToolCallRepository toolCalls, CopilotProperties props,
                       ObjectProvider<ToolAccessPolicy> policies) {
        this.registryProvider = registryProvider;
        this.runner = runner;
        this.toolCalls = toolCalls;
        this.props = props;
        this.policies = policies;
    }

    private ToolRegistry registry() {
        return registryProvider.getObject();
    }

    /** 校验通过的一步：seq 从 1 起。 */
    public record PlanStep(int seq, PlatformTool tool, Map<String, Object> args, ToolRisk risk,
                           String projectId) {
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
            steps.add(new PlanStep(seq, tool, validated, risk, projectId));
        }
        if (projectIds.size() > 1) {
            throw new ToolArgs.InvalidArgumentException("计划内所有步骤必须在同一个项目（跨项目请拆成多份计划）");
        }
        return new ValidatedPlan(goal, steps, aggregated,
                projectIds.isEmpty() ? null : projectIds.iterator().next());
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
                    .append(" [").append(step.risk()).append("] ").append(step.tool().spec().title()).append('\n');
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
    // 执行（确认单确认后由 PlanTool.execute 进入；按序、失败即停、不回滚）
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
            // 1) 占位符替换 + 替换后重过 schema（替换值可能超长/变型）
            Map<String, Object> args;
            try {
                args = ToolArgs.validate(step.tool().spec().parametersSchema(),
                        substitute(step.args(), dataByStep, step.seq()));
            } catch (ToolArgs.InvalidArgumentException e) {
                auditStep(ctx, step, step.args(), CopilotToolCallEntity.FAILED, null,
                        "参数失效：" + e.getMessage(), 0);
                return halt(plan, step, ToolResult.INVALID_ARGUMENTS,
                        "参数失效：" + e.getMessage(), report, ctx);
            }
            // 2) 项目级权限（对象级归属已在 validate 重解析）
            ToolResult denied = ToolAccess.check(policies.getIfAvailable(), ctx.actor(),
                    step.projectId(), step.risk(), step.tool().spec().name());
            if (denied != null) {
                auditStep(ctx, step, args, CopilotToolCallEntity.REJECTED, null, denied.errorMessage(), 0);
                report.add(stepReport(step, "REJECTED", null, denied.errorMessage()));
                return halt(plan, step, ToolResult.PERMISSION_DENIED, denied.errorMessage(), report, ctx);
            }
            // 3) 执行（SecurityContext 在 runner 内合成）
            ToolContext stepCtx = new ToolContext(ctx.turnId(), ctx.actionId(), ctx.actor(), ctx.role(),
                    ctx.channel(), ctx.externalChatId(), ctx.senderId(), ctx.personalActor(),
                    isBlank(step.projectId()) ? ctx.projectId() : step.projectId(), ctx.projectName(), ctx.now());
            long t0 = System.currentTimeMillis();
            ToolResult result = runner.run(step.tool(),
                    new ToolCall("plan_" + ctx.actionId() + "_" + step.seq(), step.tool().spec().name(), args),
                    stepCtx);
            long duration = System.currentTimeMillis() - t0;
            auditStep(ctx, step, args, result.success() ? CopilotToolCallEntity.OK : CopilotToolCallEntity.FAILED,
                    result.summary(), result.errorMessage(), duration);
            if (!result.success()) {
                report.add(stepReport(step, "FAILED", null, result.errorMessage()));
                return halt(plan, step, result.errorCode() == null ? ToolResult.UPSTREAM_ERROR : result.errorCode(),
                        result.errorMessage() == null ? "未知原因" : result.errorMessage(), report, ctx);
            }
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

    private void auditStep(ToolContext ctx, PlanStep step, Map<String, Object> args, String status,
                           String summary, String error, long durationMs) {
        try {
            CopilotToolCallEntity row = new CopilotToolCallEntity();
            row.setTurnId(ctx.turnId());
            row.setActionId(ctx.actionId());
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
