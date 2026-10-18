package com.devmind.usage.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.service.UsageStatsService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.2 用量工具（M4）：按时间窗汇总 token/费用。
 * bot 轮次没有 SecurityContext，可见范围由 {@link com.devmind.common.agent.tool.ToolContext#role()}
 * 实时解析后显式传给服务层（口径同 CAP-67：非 ADMIN 强制本人，ADMIN 缺省全平台）。
 */
@Configuration
public class UsageTools {

    private static final String M = "M4";

    private final UsageStatsService usage;

    public UsageTools(UsageStatsService usage) {
        this.usage = usage;
    }

    @Bean
    public PlatformTool getUsageSummaryTool() {
        ToolSpec spec = new ToolSpec("get_usage_summary", "查询用量汇总",
                "查询最近 N 天（默认 7，最多 90）的模型用量：回合数、输入/输出/缓存 token、估算费用。"
                        + "普通用户只能看本人；ADMIN 可用 userId 查看他人、留空查看全平台。"
                        + "用户问“最近花了多少 token/多少钱”时调用。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "days": {"type": "integer", "default": 7, "minimum": 1, "maximum": 90},
                            "userId": {"type": "string", "maxLength": 64,
                                       "description": "仅 ADMIN 生效；留空=全平台"},
                            "model": {"type": "string", "maxLength": 128}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-usage", M);
        return PlatformTool.of(spec, (ToolCall call, ToolContext ctx) -> {
            Map<String, Object> args = call.args();
            boolean admin = "ADMIN".equals(ctx.role());
            String requested = admin ? ToolText.str(args, "userId") : ctx.actor();
            if (!admin && (requested == null || requested.isBlank())) {
                return ToolResult.fail(ToolResult.IDENTITY_REQUIRED, "没有说话人身份，无法查询用量");
            }
            int days = ToolText.intArg(args, "days", 7, 1, 90);
            String model = ToolText.str(args, "model");
            Instant end = Instant.now();
            Instant start = end.minus(days, ChronoUnit.DAYS);
            UsageSummary s = usage.summaryForUser(start, end, requested, model);

            String scope = admin
                    ? (requested == null ? "全平台" : "用户 " + requested)
                    : "本人";
            String text = "最近 " + days + " 天用量（" + scope + "）：\n"
                    + "- 会话 " + s.sessionCount() + " 个 / 问答 " + s.chatCount()
                    + " 个 / 回合 " + s.turnCount() + "\n"
                    + "- 输入 " + s.inputTokens() + " tokens，输出 " + s.outputTokens()
                    + " tokens，缓存读 " + s.cacheReadTokens() + "，缓存写 " + s.cacheCreationTokens() + "\n"
                    + String.format("- 估算费用 $%.4f", s.costUsd());
            // ToolResult.data 会 Map.copyOf：null 值必炸，可选维度为空时不入表
            Map<String, Object> data = ToolResult.data(
                    "days", days,
                    "costUsd", s.costUsd(),
                    "inputTokens", s.inputTokens(),
                    "outputTokens", s.outputTokens(),
                    "turnCount", s.turnCount(),
                    "sessionCount", s.sessionCount(),
                    "chatCount", s.chatCount());
            if (requested != null) {
                data.put("userId", requested);
            }
            if (model != null) {
                data.put("model", model);
            }
            return ToolResult.ok(text, text, data);
        });
    }
}
