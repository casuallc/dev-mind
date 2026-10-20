package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.notification.BotCompletionWatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CAP-75 FR-25：白名单登记 / READ 工具与 Web 轮不登记 / 失败不登记 / SESSION 取任务描述。 */
class CopilotCompletionWatchHookTest {

    private final List<TestWatchHooks.Captured> sink = new ArrayList<>();
    private final CopilotCompletionWatchHook hook = TestWatchHooks.capturing(sink);

    private static ToolContext botCtx() {
        return new ToolContext("turn-1", null, "liuchangqing", "DEVELOPER", "feishu",
                "chat-1", "sender-1", "liuchangqing", "p1", "demo", Instant.now());
    }

    private static ToolContext webCtx() {
        return new ToolContext("turn-2", null, "liuchangqing", "DEVELOPER", null,
                null, null, null, "p1", "demo", Instant.now());
    }

    @Test
    void registersBuildWatchOnTriggerBuild() {
        hook.afterSuccess("trigger_build", "触发构建", Map.of(), botCtx(),
                ToolResult.ok("已触发构建 #12", null,
                        ToolResult.data("buildId", 12L, "status", "QUEUED", "projectId", "p1")));

        assertEquals(1, sink.size());
        TestWatchHooks.Captured c = sink.get(0);
        assertEquals("BUILD", c.entityType());
        assertEquals("12", c.entityId());
        assertEquals("feishu", c.channel());
        assertEquals("chat-1", c.externalChatId());
        assertEquals("触发构建", c.label());
    }

    @Test
    void coercesIntegerId() {
        hook.afterSuccess("trigger_build", "触发构建", Map.of(), botCtx(),
                ToolResult.ok("ok", null, ToolResult.data("buildId", 7)));
        assertEquals("7", sink.get(0).entityId());
    }

    @Test
    void skipsReadToolEvenWithIdInData() {
        hook.afterSuccess("get_build", "查询构建详情", Map.of(), botCtx(),
                ToolResult.ok("构建 #12", null, ToolResult.data("buildId", 12L)));
        assertTrue(sink.isEmpty());
    }

    @Test
    void skipsWebTurnWithoutChannel() {
        hook.afterSuccess("trigger_build", "触发构建", Map.of(), webCtx(),
                ToolResult.ok("ok", null, ToolResult.data("buildId", 12L)));
        assertTrue(sink.isEmpty());
    }

    @Test
    void skipsFailedResult() {
        hook.afterSuccess("trigger_build", "触发构建", Map.of(), botCtx(),
                ToolResult.fail(ToolResult.UPSTREAM_ERROR, "炸了"));
        assertTrue(sink.isEmpty());
    }

    @Test
    void skipsWhenIdMissing() {
        hook.afterSuccess("trigger_build", "触发构建", Map.of(), botCtx(),
                ToolResult.ok("ok", null, ToolResult.data("status", "QUEUED")));
        assertTrue(sink.isEmpty());
    }

    @Test
    void sessionWatchUsesTaskSpecLabel() {
        hook.afterSuccess("start_session", "启动会话", Map.of("taskSpec", "  修登录页样式  "), botCtx(),
                ToolResult.ok("已启动会话 abcd1234", null,
                        ToolResult.data("sessionId", "s-abcd1234", "status", "RUNNING")));

        assertEquals(1, sink.size());
        TestWatchHooks.Captured c = sink.get(0);
        assertEquals(BotCompletionWatch.SESSION, c.entityType());
        assertEquals("s-abcd1234", c.entityId());
        assertEquals("修登录页样式", c.label());
    }

    @Test
    void sessionWatchFallsBackToSummary() {
        hook.afterSuccess("start_work_item_session", "启动工作项会话", Map.of("workItemId", "WI-1"),
                botCtx(),
                ToolResult.ok("已启动会话 abcd1234\n（状态 RUNNING）", null,
                        ToolResult.data("sessionId", "s-abcd1234")));
        assertEquals("已启动会话 abcd1234 （状态 RUNNING）", sink.get(0).label());
    }

    @Test
    void spiAbsentSilentlySkips() {
        CopilotCompletionWatchHook noBot = TestWatchHooks.none();
        assertDoesNotThrow(() -> noBot.afterSuccess("trigger_build", "触发构建", Map.of(), botCtx(),
                ToolResult.ok("ok", null, ToolResult.data("buildId", 12L))));
        assertTrue(sink.isEmpty());
    }

    @Test
    void reservedDeployAndTestMappings() {
        hook.afterSuccess("trigger_deploy", "触发部署", Map.of(), botCtx(),
                ToolResult.ok("ok", null, ToolResult.data("deploymentId", 5L)));
        hook.afterSuccess("run_test", "触发测试", Map.of(), botCtx(),
                ToolResult.ok("ok", null, ToolResult.data("testRunId", 9L)));
        assertEquals("DEPLOYMENT", sink.get(0).entityType());
        assertEquals("5", sink.get(0).entityId());
        assertEquals("TEST_RUN", sink.get(1).entityType());
        assertEquals("9", sink.get(1).entityId());
    }
}
