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

    private final CopilotToolRunner runner = new CopilotToolRunner(new CopilotProperties());

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
