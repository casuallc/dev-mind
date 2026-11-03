package com.devmind.copilot.service;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.copilot.config.CopilotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-108 FR-05：工具线程内建立授权执行上下文——主体=actor、来源按渠道判定、
 * actionId 透传（确认凭据），执行完毕清理不泄漏。
 */
class CopilotToolRunnerContextTest {

    private final AtomicReference<ExecutionContext> seen = new AtomicReference<>();

    private final PlatformTool probe = PlatformTool.of(
            new ToolSpec("probe", "探针", "探针", "{\"type\":\"object\"}",
                    com.devmind.common.agent.tool.ToolRisk.READ, "DEVELOPER", false, "test", "1"),
            (call, ctx) -> {
                seen.set(ExecutionContextHolder.current().orElse(null));
                return ToolResult.ok("ok");
            });

    private final CopilotToolRunner runner = new CopilotToolRunner(new CopilotProperties(),
            new CopilotCompletionWatchHook(null));

    private static ToolContext toolCtx(String actor, String channel, String actionId) {
        return new ToolContext("t1", actionId, actor, "DEVELOPER", channel,
                "chat1", "sender1", actor, null, null, Instant.now());
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
    }

    @Test
    void bot来源建立BOT上下文并透传确认凭据() {
        runner.run(probe, new ToolCall("c1", "probe", Map.of()), toolCtx("alice", "feishu", "act-1"));
        ExecutionContext ctx = seen.get();
        assertEquals("alice", ctx.username());
        assertEquals(ExecutionSource.BOT, ctx.source());
        assertTrue(ctx.aiSourced());
        assertEquals("act-1", ctx.actionId());
        assertEquals("t1", ctx.turnId());
    }

    @Test
    void 无渠道按COPILOT_WEB() {
        runner.run(probe, new ToolCall("c1", "probe", Map.of()), toolCtx("alice", null, null));
        assertEquals(ExecutionSource.COPILOT_WEB, seen.get().source());
        assertTrue(seen.get().aiSourced());
    }

    @Test
    void 匿名actor不建立上下文() {
        runner.run(probe, new ToolCall("c1", "probe", Map.of()), toolCtx("local", "feishu", null));
        assertNull(seen.get());
    }

    @Test
    void 执行后上下文不泄漏() {
        runner.run(probe, new ToolCall("c1", "probe", Map.of()), toolCtx("alice", "feishu", null));
        assertFalse(ExecutionContextHolder.current().isPresent());
    }
}
