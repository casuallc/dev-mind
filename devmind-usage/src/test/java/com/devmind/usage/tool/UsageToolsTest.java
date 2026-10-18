package com.devmind.usage.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.service.UsageStatsService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CAP-76 M4：用量工具的可见范围口径（非 ADMIN 强制本人，ADMIN 可查他人/全平台）。 */
class UsageToolsTest {

    private static final UsageSummary SUMMARY =
            new UsageSummary(1.25, 1000, 200, 50, 10, 7, 2, 3);

    /** 只截获 summaryForUser 的 userId，避免拉起整个 CAP-67 仓储树。 */
    private static final class StubUsage extends UsageStatsService {
        String receivedUser;
        boolean called;

        StubUsage() {
            super(null, null, null, null, null, null);
        }

        @Override
        public UsageSummary summaryForUser(Instant start, Instant end, String userId, String model) {
            this.receivedUser = userId;
            this.called = true;
            return SUMMARY;
        }
    }

    private static ToolContext ctx(String actor, String role) {
        return new ToolContext("t1", null, actor, role, "feishu", "chat-1", "sender-1",
                null, null, null, Instant.now());
    }

    @Test
    void nonAdminIgnoresModelSuppliedUser() {
        StubUsage usage = new StubUsage();
        PlatformTool tool = new UsageTools(usage).getUsageSummaryTool();
        ToolResult r = tool.execute(new ToolCall("c1", "get_usage_summary", Map.of("userId", "bob")),
                ctx("alice", "DEVELOPER"));

        assertTrue(r.success());
        assertEquals("alice", usage.receivedUser);
        assertTrue(r.summary().contains("本人"), r.summary());
        assertTrue(r.summary().contains("输出 200 tokens"), r.summary());
    }

    @Test
    void adminCanQueryOtherUserOrPlatform() {
        StubUsage usage = new StubUsage();
        PlatformTool tool = new UsageTools(usage).getUsageSummaryTool();

        ToolResult other = tool.execute(new ToolCall("c1", "get_usage_summary", Map.of("userId", "bob")),
                ctx("root", "ADMIN"));
        assertTrue(other.success());
        assertEquals("bob", usage.receivedUser);
        assertTrue(other.summary().contains("用户 bob"), other.summary());

        ToolResult all = tool.execute(new ToolCall("c2", "get_usage_summary", Map.of()),
                ctx("root", "ADMIN"));
        assertTrue(all.success());
        assertNull(usage.receivedUser);
        assertTrue(all.summary().contains("全平台"), all.summary());
    }

    @Test
    void nonAdminWithoutActorIsRejected() {
        StubUsage usage = new StubUsage();
        PlatformTool tool = new UsageTools(usage).getUsageSummaryTool();
        ToolResult r = tool.execute(new ToolCall("c1", "get_usage_summary", Map.of()),
                ctx(null, "VIEWER"));

        assertFalse(r.success());
        assertEquals(ToolResult.IDENTITY_REQUIRED, r.errorCode());
        assertFalse(usage.called);
    }
}
