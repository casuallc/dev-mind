package com.devmind.copilot.service;

import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.notification.BotCompletionWatch;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 作业终态推送登记钩子（CAP-75 FR-25）：工具执行漏斗（{@link CopilotToolRunner}）成功后调用，
 * 按<b>工具名白名单</b>把异步作业的实体 id 登记进 bot watch——作业终态后来源聊天收到推送。
 *
 * <p>只认「受理了异步作业」的工具：READ 工具（get_build 等同样返回 id）不在白名单不登记；
 * 仅 bot 来源轮（ToolContext.channel/externalChatId 非空）登记，Web 轮静默跳过。
 * SPI 缺席（bot 模块未装配）或登记失败都不阻断执行回执（推送是增强能力）。</p>
 */
@Component
public class CopilotCompletionWatchHook {

    private static final Logger log = LoggerFactory.getLogger(CopilotCompletionWatchHook.class);

    /** 工具名 → （结果 data 键, 订阅实体类型） */
    private record WatchTarget(String dataKey, String entityType) {
    }

    private static final Map<String, WatchTarget> TARGETS = Map.of(
            "trigger_build", new WatchTarget("buildId", "BUILD"),
            // 预留：部署/测试触发工具未实现，映射先行——工具落地即自动接入推送
            "trigger_deploy", new WatchTarget("deploymentId", "DEPLOYMENT"),
            "trigger_test", new WatchTarget("testRunId", "TEST_RUN"),
            "run_test", new WatchTarget("testRunId", "TEST_RUN"),
            // FR-20 缺口补齐：智能体路径起的会话此前没有 watch，终态不回聊天
            "start_session", new WatchTarget("sessionId", BotCompletionWatch.SESSION),
            "start_work_item_session", new WatchTarget("sessionId", BotCompletionWatch.SESSION),
            "start_requirement_dev", new WatchTarget("sessionId", BotCompletionWatch.SESSION),
            "start_requirement_plan", new WatchTarget("sessionId", BotCompletionWatch.SESSION),
            "resume_session", new WatchTarget("sessionId", BotCompletionWatch.SESSION));

    private static final int LABEL_MAX = 200;

    private final ObjectProvider<BotCompletionWatch> watchProvider;

    public CopilotCompletionWatchHook(ObjectProvider<BotCompletionWatch> watchProvider) {
        this.watchProvider = watchProvider;
    }

    /** 成功后登记（任何失败只记日志）。toolTitle 作作业 label；SESSION 的 label 取任务描述。 */
    public void afterSuccess(String toolName, String toolTitle, Map<String, Object> args,
                             ToolContext ctx, ToolResult result) {
        try {
            if (result == null || !result.success()) {
                return;
            }
            WatchTarget target = TARGETS.get(toolName);
            if (target == null || ctx == null
                    || isBlank(ctx.channel()) || isBlank(ctx.externalChatId())) {
                return;
            }
            String entityId = idString(result.data().get(target.dataKey()));
            if (entityId == null) {
                return;
            }
            BotCompletionWatch watch = watchProvider.getIfAvailable();
            if (watch == null) {
                return; // bot 模块未装配：推送能力缺席，静默跳过
            }
            String label = BotCompletionWatch.SESSION.equals(target.entityType())
                    ? sessionLabel(args, result) : toolTitle;
            watch.watch(target.entityType(), entityId, ctx.channel(), ctx.externalChatId(), label);
        } catch (Exception e) {
            log.warn("bot 作业订阅登记失败（不阻断执行回执）: tool={} err={}", toolName, e.getMessage());
        }
    }

    /** 会话 watch 的 label = 任务描述（FR-20 推送文案用）；缺省退回结果摘要一行。 */
    private static String sessionLabel(Map<String, Object> args, ToolResult result) {
        if (args != null && args.get("taskSpec") instanceof String s && !s.isBlank()) {
            return cut(s.strip());
        }
        return result.summary() == null ? null : cut(oneLine(result.summary()));
    }

    /** Number 取 long 串（构建号等 Long），字符串原样；其余形态不登记。 */
    private static String idString(Object raw) {
        if (raw instanceof Number n) {
            return Long.toString(n.longValue());
        }
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String cut(String text) {
        return text.length() <= LABEL_MAX ? text : text.substring(0, LABEL_MAX);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
