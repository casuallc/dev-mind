package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.copilot.config.CopilotProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP-76 FR-05：工具执行线程必须带上本轮 actor 的 SecurityContext。
 *
 * <p>回归背景：工具体在单独的虚拟线程里跑，SecurityContextHolder 默认 THREADLOCAL 不跨线程；
 * 只在调用线程合成身份会让 {@code identity.currentActor()} 回退 {@code local}，
 * 按 actor 取数的工具（工作日志/通知等）读到空数据。</p>
 */
class CopilotToolRunnerTest {

    private final CopilotToolRunner runner =
            new CopilotToolRunner(new CopilotProperties(), TestWatchHooks.none());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void toolBodyRunsWithActorSecurityContext() {
        AtomicReference<String> seen = new AtomicReference<>();
        PlatformTool tool = PlatformTool.of(spec(), (call, ctx) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            seen.set(auth != null && auth.getPrincipal() instanceof DevMindPrincipal p
                    ? p.username() : null);
            return ToolResult.ok("ok");
        });
        ToolResult result = runner.run(tool, new ToolCall("call-1", "whoami", Map.of()), context());
        assertTrue(result.success(), result.errorMessage());
        assertEquals("liuchangqing", seen.get());
    }

    @Test
    void callerThreadContextIsRestored() {
        PlatformTool tool = PlatformTool.of(spec(), (call, ctx) -> ToolResult.ok("ok"));
        runner.run(tool, new ToolCall("call-2", "whoami", Map.of()), context());
        assertEquals(null, SecurityContextHolder.getContext().getAuthentication());
    }

    /** CAP-75 FR-25：白名单工具在 bot 来源轮执行成功 → 经漏斗登记终态推送订阅。 */
    @Test
    void runRegistersWatchForWhitelistedTool() {
        List<TestWatchHooks.Captured> sink = new ArrayList<>();
        CopilotToolRunner watching =
                new CopilotToolRunner(new CopilotProperties(), TestWatchHooks.capturing(sink));
        ToolSpec triggerSpec = new ToolSpec("trigger_build", "触发构建", "测试用",
                "{\"type\":\"object\",\"properties\":{}}",
                ToolRisk.WRITE, "DEVELOPER", true, "test", "test");
        PlatformTool tool = PlatformTool.of(triggerSpec, (call, ctx) ->
                ToolResult.ok("已触发构建 #12", null, ToolResult.data("buildId", 12L)));

        ToolResult result = watching.run(tool, new ToolCall("call-3", "trigger_build", Map.of()), context());

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, sink.size());
        assertEquals("BUILD", sink.get(0).entityType());
        assertEquals("12", sink.get(0).entityId());
        assertEquals("chat-1", sink.get(0).externalChatId());
    }

    /** FR-25：非白名单工具（READ）执行成功也不登记。 */
    @Test
    void runSkipsWatchForReadTool() {
        List<TestWatchHooks.Captured> sink = new ArrayList<>();
        CopilotToolRunner watching =
                new CopilotToolRunner(new CopilotProperties(), TestWatchHooks.capturing(sink));
        PlatformTool tool = PlatformTool.of(spec(), (call, ctx) -> ToolResult.ok("ok"));

        watching.run(tool, new ToolCall("call-4", "whoami", Map.of()), context());

        assertTrue(sink.isEmpty());
    }

    private static ToolSpec spec() {
        return new ToolSpec("whoami", "身份自检", "测试用只读工具",
                "{\"type\":\"object\",\"properties\":{}}",
                ToolRisk.READ, "VIEWER", false, "test", "test");
    }

    private static ToolContext context() {
        return new ToolContext("turn-1", null, "liuchangqing", "ADMIN", "feishu",
                "chat-1", "sender-1", "liuchangqing", null, null, Instant.now());
    }
}
