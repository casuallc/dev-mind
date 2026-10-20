package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolArgs;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-76 FR-33（M7）：多步计划校验/风险聚合/按序执行/占位符替换/失败即停与步骤级审计。
 */
class PlanServiceTest {

    private final List<String> invoked = new ArrayList<>();
    private final Map<String, Object> betaReceived = new LinkedHashMap<>();
    private final List<CopilotToolCallEntity> audited = new ArrayList<>();
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

    private PlanService service(PlatformTool... extra) {
        List<PlatformTool> tools = new ArrayList<>(List.of(createAlpha, createBeta, explode, dangerThing));
        tools.addAll(List.of(extra));
        ToolRegistry registry = new ToolRegistry(tools, new ObjectMapper());
        return new PlanService(provider(registry), new CopilotToolRunner(props),
                fakeRepo(audited), props, noPolicy());
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
        // 步骤级审计：两行 OK
        assertEquals(2, audited.size());
        assertTrue(audited.stream().allMatch(r -> CopilotToolCallEntity.OK.equals(r.getStatus())));
        assertEquals(List.of("create_alpha", "create_beta"),
                audited.stream().map(CopilotToolCallEntity::getToolName).toList());
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
        assertEquals(List.of(CopilotToolCallEntity.OK, CopilotToolCallEntity.FAILED,
                        CopilotToolCallEntity.REJECTED),
                audited.stream().map(CopilotToolCallEntity::getStatus).toList());
        assertEquals("前序步骤失败，未执行", audited.get(2).getError());
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
    // 工具与脚手架
    // ------------------------------------------------------------------

    private static ToolSpec spec(String name, ToolRisk risk, String schema) {
        return new ToolSpec(name, name, "测试工具 " + name, schema,
                risk, "VIEWER", false, "test", "test");
    }

    private static Map<String, Object> step(String tool, Map<String, Object> args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("args", args);
        return m;
    }

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
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public Iterator<T> iterator() {
                return List.of(bean).iterator();
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

    /** 无 mockito：用动态代理只捕获 save，其余返回空值。 */
    private static CopilotToolCallRepository fakeRepo(List<CopilotToolCallEntity> saved) {
        return (CopilotToolCallRepository) Proxy.newProxyInstance(
                PlanServiceTest.class.getClassLoader(),
                new Class<?>[]{CopilotToolCallRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName()) && args != null && args.length == 1) {
                        saved.add((CopilotToolCallEntity) args[0]);
                        return args[0];
                    }
                    Class<?> rt = method.getReturnType();
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
                });
    }
}
