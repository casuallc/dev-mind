package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.plan.JobTerminalProbe;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolArgs;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.notification.AgentChatPush;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-76 FR-33（M7）多步计划校验/风险聚合/按序执行/占位符替换/失败即停；
 * FR-34/35/36（M8）异步分发收口与推送、await 终态等待、步骤审计生命周期。
 */
class PlanServiceTest {

    private static final Set<String> TERMINAL = Set.of(CopilotToolCallEntity.OK,
            CopilotToolCallEntity.FAILED, CopilotToolCallEntity.REJECTED);

    private final List<String> invoked = new ArrayList<>();
    private final Map<String, Object> betaReceived = new LinkedHashMap<>();
    private final List<CopilotToolCallEntity> audited = new ArrayList<>();
    private final Map<String, CopilotActionEntity> actionStore = new HashMap<>();
    private final List<String> pushed = new ArrayList<>();
    private final CopilotProperties props = new CopilotProperties();

    private final PlatformTool createAlpha = PlatformTool.of(
            spec("create_alpha", ToolRisk.WRITE, "{\"type\":\"object\",\"properties\":{}}"),
            (call, ctx) -> {
                invoked.add("create_alpha");
                return ToolResult.ok("建成 A-1", null, ToolResult.data("alphaId", "A-1", "code", "AL-1"));
            },
            args -> "p1");

    private final PlatformTool createBeta = PlatformTool.of(
            spec("create_beta", ToolRisk.WRITE,
                    "{\"type\":\"object\",\"properties\":{\"alphaId\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}},"
                            + "\"required\":[\"alphaId\"]}"),
            (call, ctx) -> {
                invoked.add("create_beta");
                betaReceived.putAll(call.args());
                return ToolResult.ok("建成 B-1", null, ToolResult.data("betaId", "B-1"));
            },
            args -> "p1");

    private final PlatformTool explode = PlatformTool.of(
            spec("explode", ToolRisk.WRITE, "{\"type\":\"object\",\"properties\":{}}"),
            (call, ctx) -> {
                invoked.add("explode");
                return ToolResult.fail(ToolResult.UPSTREAM_ERROR, "炸了");
            },
            args -> "p1");

    private final PlatformTool otherProject = PlatformTool.of(
            spec("other_project_thing", ToolRisk.WRITE, "{\"type\":\"object\",\"properties\":{}}"),
            (call, ctx) -> ToolResult.ok("ok"),
            args -> "p2");

    private final PlatformTool dangerThing = PlatformTool.of(
            spec("danger_thing", ToolRisk.DANGEROUS, "{\"type\":\"object\",\"properties\":{}}"),
            (call, ctx) -> ToolResult.ok("ok"));

    private final PlatformTool startBuild = PlatformTool.of(
            spec("start_build", ToolRisk.WRITE, "{\"type\":\"object\",\"properties\":{}}"),
            (call, ctx) -> {
                invoked.add("start_build");
                return ToolResult.ok("构建已触发", null, ToolResult.data("buildId", 7));
            },
            args -> "p1");

    private final AgentChatPush chatPush = (channel, externalChatId, text) ->
            pushed.add(channel + "|" + externalChatId + "|" + text);

    private List<PlatformTool> baseTools() {
        return new ArrayList<>(List.of(createAlpha, createBeta, explode, dangerThing));
    }

    private PlanService service(PlatformTool... extra) {
        return serviceWith(List.of(), extra);
    }

    private PlanService serviceWith(List<JobTerminalProbe> probes, PlatformTool... extra) {
        List<PlatformTool> tools = baseTools();
        tools.addAll(List.of(extra));
        return newPlanService(tools, probes);
    }

    private PlanService newPlanService(List<PlatformTool> tools, List<JobTerminalProbe> probes) {
        ToolRegistry registry = new ToolRegistry(tools, new ObjectMapper());
        return new PlanService(provider(registry), new CopilotToolRunner(props, TestWatchHooks.none()),
                fakeToolCallRepo(audited), fakeActionRepo(actionStore), props, noPolicy(),
                new PlanProbes(providers(probes)), provider(chatPush));
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    void rejectsSingleStep() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("单步操作"), e.getMessage());
    }

    @Test
    void rejectsOverMaxSteps() {
        props.setPlanMaxSteps(3);
        PlanService plans = service();
        Map<String, Object> plan = plan(
                step("create_alpha", Map.of()), step("create_alpha", Map.of()),
                step("create_alpha", Map.of()), step("create_alpha", Map.of()));
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan, "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("最多 3 步"), e.getMessage());
    }

    @Test
    void rejectsNestedPlan() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("submit_plan", Map.of("goal", "套娃", "steps", List.of()))),
                        "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("嵌套"), e.getMessage());
    }

    @Test
    void rejectsUnknownTool() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("no_such_tool", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("工具不存在"), e.getMessage());
    }

    @Test
    void rejectsStepSchemaViolation() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("create_beta", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("第 2 步"), e.getMessage());
    }

    @Test
    void rejectsFutureOrSelfPlaceholder() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException future = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_beta", Map.of("alphaId", "${step2.alphaId}")),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(future.getMessage().contains("只能引用序号更小的步骤"), future.getMessage());

        ToolArgs.InvalidArgumentException self = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("create_beta", Map.of("alphaId", "${step2.alphaId}"))),
                        "DEVELOPER", null, ctx()));
        assertTrue(self.getMessage().contains("只能引用序号更小的步骤"), self.getMessage());
    }

    @Test
    void rejectsMalformedPlaceholder() {
        PlanService plans = service();
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("create_beta", Map.of("alphaId", "${第一步.alphaId}"))),
                        "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("占位符格式非法"), e.getMessage());
    }

    @Test
    void rejectsCrossProject() {
        PlanService plans = service(otherProject);
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        step("create_alpha", Map.of()),
                        step("other_project_thing", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("同一个项目"), e.getMessage());
    }

    @Test
    void validatesAndAggregatesRiskAndProject() {
        PlanService plans = service();
        PlanService.ValidatedPlan plan = plans.validate(plan(
                step("create_alpha", Map.of()),
                step("create_beta", Map.of("alphaId", "${step1.alphaId}"))),
                "DEVELOPER", null, ctx());
        assertEquals(2, plan.steps().size());
        assertEquals(ToolRisk.WRITE, plan.risk());
        assertEquals("p1", plan.projectId());

        PlanService.ValidatedPlan dangerous = plans.validate(plan(
                step("create_alpha", Map.of()),
                step("danger_thing", Map.of())), "DEVELOPER", null, ctx());
        assertEquals(ToolRisk.DANGEROUS, dangerous.risk());
    }

    @Test
    void riskOfFailsClosed() {
        PlanService plans = service();
        assertEquals(ToolRisk.WRITE, plans.riskOf(plan(
                step("create_alpha", Map.of()), step("create_beta", Map.of("alphaId", "x")))));
        assertEquals(ToolRisk.DANGEROUS, plans.riskOf(plan(
                step("create_alpha", Map.of()), step("danger_thing", Map.of()))));
        assertEquals(ToolRisk.DANGEROUS, plans.riskOf(plan(
                step("create_alpha", Map.of()), step("no_such_tool", Map.of()))));
        assertEquals(ToolRisk.DANGEROUS, plans.riskOf(Map.of()));
    }

    // ------------------------------------------------------------------
    // await 校验（FR-35）
    // ------------------------------------------------------------------

    @Test
    void rejectsAwaitWhenProbeMissing() {
        PlanService plans = service(startBuild);
        ToolArgs.InvalidArgumentException e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        awaitStep("start_build", Map.of(), Map.of("kind", "BUILD", "idField", "buildId")),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(e.getMessage().contains("探针未装配"), e.getMessage());
    }

    @Test
    void rejectsBadAwaitSpec() {
        PlanService plans = serviceWith(List.of(probe(JobState.SUCCEEDED, null)), startBuild);
        ToolArgs.InvalidArgumentException badKind = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        awaitStep("start_build", Map.of(), Map.of("kind", "DEPLOY", "idField", "buildId")),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(badKind.getMessage().contains("await.kind 不合法"), badKind.getMessage());

        ToolArgs.InvalidArgumentException noIdField = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        awaitStep("start_build", Map.of(), Map.of("kind", "BUILD")),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(noIdField.getMessage().contains("缺少 idField"), noIdField.getMessage());

        ToolArgs.InvalidArgumentException badTimeout = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        awaitStep("start_build", Map.of(),
                                Map.of("kind", "BUILD", "idField", "buildId", "timeoutMinutes", 0)),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(badTimeout.getMessage().contains("timeoutMinutes"), badTimeout.getMessage());

        ToolArgs.InvalidArgumentException unknownKey = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> plans.validate(plan(
                        awaitStep("start_build", Map.of(),
                                Map.of("kind", "BUILD", "idField", "buildId", "bogus", 1)),
                        step("create_alpha", Map.of())), "DEVELOPER", null, ctx()));
        assertTrue(unknownKey.getMessage().contains("未知字段"), unknownKey.getMessage());
    }

    @Test
    void acceptsAwaitWithProbe() {
        PlanService plans = serviceWith(List.of(probe(JobState.SUCCEEDED, null)), startBuild);
        PlanService.ValidatedPlan plan = plans.validate(plan(
                awaitStep("start_build", Map.of(), Map.of("kind", "BUILD", "idField", "buildId")),
                step("create_alpha", Map.of())), "DEVELOPER", null, ctx());
        assertNotNull(plan.steps().get(0).await());
        assertEquals("BUILD", plan.steps().get(0).await().kind());
        assertEquals(30, plan.steps().get(0).await().timeoutMinutes());
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    @Test
    void executesInOrderWithPlaceholderSubstitution() {
        PlanService plans = service();
        ToolResult result = plans.execute(plan(
                step("create_alpha", Map.of()),
                step("create_beta", Map.of(
                        "alphaId", "${step1.alphaId}",
                        "note", "来自 ${step1.code}"))), ctx());
        assertTrue(result.success(), result.errorMessage());
        assertEquals(List.of("create_alpha", "create_beta"), invoked);
        assertEquals("A-1", betaReceived.get("alphaId"));
        assertEquals("来自 AL-1", betaReceived.get("note"));
        assertEquals(2, result.data().get("completed"));
        assertEquals(2, result.data().get("total"));
        // 步骤级审计生命周期：起步 RUNNING，收口 OK（快照逐次留痕）
        assertEquals(CopilotToolCallEntity.RUNNING, audited.get(0).getStatus());
        List<CopilotToolCallEntity> terminal = audited.stream()
                .filter(r -> TERMINAL.contains(r.getStatus())).toList();
        assertEquals(2, terminal.size());
        assertTrue(terminal.stream().allMatch(r -> CopilotToolCallEntity.OK.equals(r.getStatus())));
        assertEquals(List.of("create_alpha", "create_beta"),
                terminal.stream().map(CopilotToolCallEntity::getToolName).toList());
        assertEquals(List.of(1, 2), terminal.stream().map(CopilotToolCallEntity::getStepSeq).toList());
    }

    @Test
    void haltsOnFailureWithoutRollback() {
        PlanService plans = service();
        ToolResult result = plans.execute(plan(
                step("create_alpha", Map.of()),
                step("explode", Map.of()),
                step("create_beta", Map.of("alphaId", "A-1"))), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("已完成 1/3 步"), result.errorMessage());
        assertTrue(result.errorMessage().contains("第 2 步"), result.errorMessage());
        assertTrue(result.errorMessage().contains("不回滚"), result.errorMessage());
        // beta 未执行
        assertEquals(List.of("create_alpha", "explode"), invoked);
        // 审计：OK + FAILED + REJECTED（未执行的后续步骤也留痕）
        List<CopilotToolCallEntity> terminal = audited.stream()
                .filter(r -> TERMINAL.contains(r.getStatus())).toList();
        assertEquals(List.of(CopilotToolCallEntity.OK, CopilotToolCallEntity.FAILED,
                        CopilotToolCallEntity.REJECTED),
                terminal.stream().map(CopilotToolCallEntity::getStatus).toList());
        assertEquals("前序步骤失败，未执行", terminal.get(2).getError());
    }

    @Test
    void failsWhenPlaceholderFieldMissing() {
        PlanService plans = service();
        ToolResult result = plans.execute(plan(
                step("create_alpha", Map.of()),
                step("create_beta", Map.of("alphaId", "${step1.no_such_field}"))), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("未产出字段 no_such_field"), result.errorMessage());
        assertEquals(List.of("create_alpha"), invoked);
    }

    @Test
    void executeRevalidatesStalePlan() {
        // 确认单创建后工具被摘除/参数被改：执行期全量重校验直接失败，不执行任何步骤
        PlanService plans = service();
        ToolResult result = plans.execute(plan(
                step("create_alpha", Map.of()),
                step("no_such_tool", Map.of())), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("计划已失效"), result.errorMessage());
        assertTrue(invoked.isEmpty());
    }

    // ------------------------------------------------------------------
    // await 执行（FR-35）
    // ------------------------------------------------------------------

    @Test
    void awaitSuccessContinuesPlan() {
        PlanService plans = serviceWith(List.of(probe(JobState.SUCCEEDED, null)), startBuild);
        ToolResult result = plans.execute(plan(
                awaitStep("start_build", Map.of(), Map.of("kind", "BUILD", "idField", "buildId")),
                step("create_alpha", Map.of())), ctx());
        assertTrue(result.success(), result.errorMessage());
        assertEquals(List.of("start_build", "create_alpha"), invoked);
        // 步骤 1 经历 RUNNING → WAITING → OK
        assertTrue(audited.stream().anyMatch(r -> CopilotToolCallEntity.WAITING.equals(r.getStatus())
                && Integer.valueOf(1).equals(r.getStepSeq())));
        List<CopilotToolCallEntity> terminal = audited.stream()
                .filter(r -> TERMINAL.contains(r.getStatus())).toList();
        assertEquals(List.of(CopilotToolCallEntity.OK, CopilotToolCallEntity.OK),
                terminal.stream().map(CopilotToolCallEntity::getStatus).toList());
    }

    @Test
    void awaitFailureHaltsPlan() {
        PlanService plans = serviceWith(List.of(probe(JobState.FAILED, "编译失败：符号不存在")), startBuild);
        ToolResult result = plans.execute(plan(
                awaitStep("start_build", Map.of(), Map.of("kind", "BUILD", "idField", "buildId")),
                step("create_alpha", Map.of())), ctx());
        assertFalse(result.success());
        assertEquals(ToolResult.UPSTREAM_ERROR, result.errorCode());
        assertTrue(result.errorMessage().contains("BUILD 作业 7 失败"), result.errorMessage());
        assertTrue(result.errorMessage().contains("编译失败"), result.errorMessage());
        // 第 2 步未执行
        assertEquals(List.of("start_build"), invoked);
        assertEquals(0, result.data().get("completed"));
    }

    @Test
    void awaitTimeoutHaltsPlan() {
        List<PlatformTool> tools = baseTools();
        tools.add(startBuild);
        ToolRegistry registry = new ToolRegistry(tools, new ObjectMapper());
        AtomicLong fakeNow = new AtomicLong();
        PlanService plans = new PlanService(provider(registry),
                new CopilotToolRunner(props, TestWatchHooks.none()),
                fakeToolCallRepo(audited), fakeActionRepo(actionStore), props, noPolicy(),
                new PlanProbes(providers(List.of(probe(JobState.RUNNING, null)))), provider(chatPush)) {
            @Override
            long nowMillis() {
                return fakeNow.addAndGet(30_000);
            }

            @Override
            void sleepAwait(long pollMs) {
                // 虚拟时间驱动，不真实睡眠
            }
        };
        ToolResult result = plans.execute(plan(
                awaitStep("start_build", Map.of(),
                        Map.of("kind", "BUILD", "idField", "buildId", "timeoutMinutes", 1)),
                step("create_alpha", Map.of())), ctx());
        assertFalse(result.success());
        assertEquals(ToolResult.TIMEOUT, result.errorCode());
        assertTrue(result.errorMessage().contains("超时"), result.errorMessage());
        assertTrue(result.errorMessage().contains("勿直接重试"), result.errorMessage());
    }

    @Test
    void awaitMissingIdFieldHalts() {
        PlatformTool noId = PlatformTool.of(
                spec("start_nothing", ToolRisk.WRITE, "{\"type\":\"object\",\"properties\":{}}"),
                (call, ctx) -> ToolResult.ok("ok", null, ToolResult.data("foo", "bar")),
                args -> "p1");
        PlanService plans = serviceWith(List.of(probe(JobState.SUCCEEDED, null)), noId);
        ToolResult result = plans.execute(plan(
                awaitStep("start_nothing", Map.of(), Map.of("kind", "BUILD", "idField", "buildId")),
                step("create_alpha", Map.of())), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("无法等待作业终态"), result.errorMessage());
    }

    // ------------------------------------------------------------------
    // 异步分发与收口（FR-34）
    // ------------------------------------------------------------------

    @Test
    void dispatchAsyncFinalizesAndPushes() throws Exception {
        PlanService plans = service();
        CopilotActionEntity action = newAction(CopilotActionEntity.EXECUTING, plan(
                step("create_alpha", Map.of()),
                step("create_beta", Map.of("alphaId", "${step1.alphaId}"))));
        plans.dispatchAsync(action.getId(), ctx());
        awaitActionSettled(action);
        assertEquals(CopilotActionEntity.SUCCEEDED, action.getStatus());
        assertNotNull(action.getResultJson());
        assertTrue(action.getResultJson().contains("\"completed\":2"), action.getResultJson());
        // 汇总审计行：submit_plan OK、无 stepSeq
        assertTrue(audited.stream().anyMatch(r -> PlanService.TOOL_NAME.equals(r.getToolName())
                && CopilotToolCallEntity.OK.equals(r.getStatus()) && r.getStepSeq() == null));
        // 终态推送到来源聊天
        assertEquals(1, pushed.size());
        assertTrue(pushed.get(0).startsWith("feishu|chat-1|✅"), pushed.get(0));
        assertTrue(pushed.get(0).contains("测试计划"), pushed.get(0));
    }

    @Test
    void dispatchAsyncSkipsWhenActionNotExecuting() throws Exception {
        PlanService plans = service();
        CopilotActionEntity action = newAction(CopilotActionEntity.PENDING, plan(
                step("create_alpha", Map.of()), step("create_beta", Map.of("alphaId", "x"))));
        plans.dispatchAsync(action.getId(), ctx());
        Thread.sleep(300);
        assertEquals(CopilotActionEntity.PENDING, action.getStatus());
        assertTrue(invoked.isEmpty());
        assertTrue(pushed.isEmpty());
    }

    @Test
    void dispatchAsyncFailedPlanPushesFailure() throws Exception {
        PlanService plans = service();
        CopilotActionEntity action = newAction(CopilotActionEntity.EXECUTING, plan(
                step("explode", Map.of()),
                step("create_alpha", Map.of())));
        plans.dispatchAsync(action.getId(), ctx());
        awaitActionSettled(action);
        assertEquals(CopilotActionEntity.FAILED, action.getStatus());
        assertNotNull(action.getError());
        assertEquals(1, pushed.size());
        assertTrue(pushed.get(0).contains("❌"), pushed.get(0));
    }

    private void awaitActionSettled(CopilotActionEntity action) throws InterruptedException {
        for (int i = 0; i < 200 && CopilotActionEntity.EXECUTING.equals(action.getStatus()); i++) {
            Thread.sleep(50);
        }
    }

    private CopilotActionEntity newAction(String status, Map<String, Object> planArgs) {
        CopilotActionEntity action = new CopilotActionEntity();
        action.setId("act-" + actionStore.size());
        action.setTurnId("turn-1");
        action.setToolName(PlanService.TOOL_NAME);
        action.setArgsJson(CopilotJson.write(planArgs));
        action.setRisk(ToolRisk.WRITE.name());
        action.setStatus(status);
        action.setCreatedAt(Instant.now());
        action.setExpiresAt(Instant.now().plusSeconds(600));
        actionStore.put(action.getId(), action);
        return action;
    }

    // ------------------------------------------------------------------
    // 工具与脚手架
    // ------------------------------------------------------------------

    private static ToolSpec spec(String name, ToolRisk risk, String schema) {
        return new ToolSpec(name, name, "测试工具 " + name, schema,
                risk, "VIEWER", false, "test", "test");
    }

    private static JobTerminalProbe probe(JobState state, String failureSummary) {
        return new JobTerminalProbe() {
            @Override
            public String kind() {
                return "BUILD";
            }

            @Override
            public JobState probe(String entityId, String actor) {
                return state;
            }

            @Override
            public String failureSummary(String entityId) {
                return failureSummary;
            }
        };
    }

    private static Map<String, Object> step(String tool, Map<String, Object> args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("args", args);
        return m;
    }

    private static Map<String, Object> awaitStep(String tool, Map<String, Object> args,
                                                 Map<String, Object> await) {
        Map<String, Object> m = step(tool, args);
        m.put("await", await);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> plan(Map<String, Object>... steps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal", "测试计划");
        m.put("steps", List.of(steps));
        return m;
    }

    private static ToolContext ctx() {
        return new ToolContext("turn-1", "action-1", "liuchangqing", "ADMIN", "feishu",
                "chat-1", "sender-1", "liuchangqing", null, null, Instant.now());
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return providers(bean == null ? List.of() : List.of(bean));
    }

    private static <T> ObjectProvider<T> providers(List<T> beans) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return beans.isEmpty() ? null : beans.get(0);
            }

            @Override
            public T getObject() {
                return beans.isEmpty() ? null : beans.get(0);
            }

            @Override
            public Iterator<T> iterator() {
                return beans.iterator();
            }
        };
    }

    private static ObjectProvider<ToolAccessPolicy> noPolicy() {
        return new ObjectProvider<>() {
            @Override
            public ToolAccessPolicy getObject(Object... args) {
                return null;
            }

            @Override
            public ToolAccessPolicy getObject() {
                return null;
            }

            @Override
            public ToolAccessPolicy getIfAvailable() {
                return null;
            }

            @Override
            public Iterator<ToolAccessPolicy> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    /**
     * 无 mockito：动态代理捕获 save 的<b>快照</b>（审计行有 RUNNING→OK 生命周期，
     * 同一实体会被多次 save，直接存引用只剩终态、看不到过程）。
     */
    private static CopilotToolCallRepository fakeToolCallRepo(List<CopilotToolCallEntity> saved) {
        return (CopilotToolCallRepository) Proxy.newProxyInstance(
                PlanServiceTest.class.getClassLoader(),
                new Class<?>[]{CopilotToolCallRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName()) && args != null && args.length == 1) {
                        saved.add(snapshot((CopilotToolCallEntity) args[0]));
                        return args[0];
                    }
                    return emptyReturn(method.getReturnType());
                });
    }

    /** 无 mockito：确认单仓储代理，findById/save 走内存表（dispatchAsync 收口路径）。 */
    private static CopilotActionRepository fakeActionRepo(Map<String, CopilotActionEntity> store) {
        return (CopilotActionRepository) Proxy.newProxyInstance(
                PlanServiceTest.class.getClassLoader(),
                new Class<?>[]{CopilotActionRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName()) && args != null && args.length == 1) {
                        return Optional.ofNullable(store.get(args[0]));
                    }
                    if ("save".equals(method.getName()) && args != null && args.length == 1) {
                        CopilotActionEntity a = (CopilotActionEntity) args[0];
                        store.put(a.getId(), a);
                        return a;
                    }
                    return emptyReturn(method.getReturnType());
                });
    }

    private static Object emptyReturn(Class<?> rt) {
        if (rt == long.class || rt == int.class) {
            return 0;
        }
        if (rt == boolean.class) {
            return false;
        }
        if (List.class.isAssignableFrom(rt)) {
            return List.of();
        }
        if (Optional.class.isAssignableFrom(rt)) {
            return Optional.empty();
        }
        return null;
    }

    private static CopilotToolCallEntity snapshot(CopilotToolCallEntity r) {
        CopilotToolCallEntity c = new CopilotToolCallEntity();
        c.setTurnId(r.getTurnId());
        c.setActionId(r.getActionId());
        c.setStepSeq(r.getStepSeq());
        c.setToolName(r.getToolName());
        c.setArgsJson(r.getArgsJson());
        c.setRisk(r.getRisk());
        c.setStatus(r.getStatus());
        c.setResultSummary(r.getResultSummary());
        c.setError(r.getError());
        c.setDurationMs(r.getDurationMs());
        return c;
    }
}
