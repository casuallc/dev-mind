package com.devmind.session.tool;

import com.devmind.common.agent.SessionEvent;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6 会话域工具：查询会话/事件、起停会话、向会话注入输入。
 * 输入注入等于让 coding agent 执行一句话，定为 DANGEROUS（预览必须展示完整文本）。
 */
@Configuration
public class SessionTools {

    private static final String M1 = "M1";
    private static final String M2 = "M2";

    private final SessionManagerService sessions;
    private final RequirementService requirements;
    private final WorkItemService workItems;

    public SessionTools(SessionManagerService sessions, RequirementService requirements,
                        WorkItemService workItems) {
        this.sessions = sessions;
        this.requirements = requirements;
        this.workItems = workItems;
    }

    @Bean
    public PlatformTool listSessionsTool() {
        ToolSpec spec = new ToolSpec("list_sessions", "查询会话列表",
                "列出项目下的 coding 会话（id 前 8 位/状态/任务摘要/执行节点）。status 可过滤 RUNNING/DONE 等；"
                        + "未指定 projectId 时使用当前绑定项目。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "status": {"type": "string", "maxLength": 32},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-session", M1);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            List<SessionView> all = sessions.list(ToolText.str(args, "status"), projectId, null, null);
            if (projectId == null) {
                // 无项目上下文时只列本人创建的会话，避免跨项目越权浏览（项目可见范围收紧见 CAP-62）
                String actor = ctx.actor();
                all = all.stream()
                        .filter(s -> actor != null && actor.equals(s.createdBy()))
                        .toList();
            }
            List<SessionView> list = all.stream().limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("该项目下没有匹配的会话。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个会话：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SessionView s : list) {
                sb.append("\n- ").append(shortId(s.id())).append(" [").append(s.status()).append("] ")
                        .append(ToolText.oneLine(s.taskSpec(), 80));
                if (s.agentNodeId() != null) {
                    sb.append(" · 节点=").append(s.agentNodeId());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", s.id());
                row.put("status", s.status());
                row.put("taskSpec", ToolText.oneLine(s.taskSpec(), 200));
                if (s.agentNodeId() != null) {
                    row.put("agentNodeId", s.agentNodeId());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("sessions", rows));
        });
    }

    @Bean
    public PlatformTool getSessionTool() {
        ToolSpec spec = new ToolSpec("get_session", "查询会话详情",
                "查询单个会话的状态、任务说明、执行节点、token 用量与摘要。sessionId 支持完整 id。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-session", M1);
        return PlatformTool.of(spec, (call, ctx) -> {
            String sessionId = ToolText.str(call.args(), "sessionId");
            if (sessionId == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供会话 id");
            }
            SessionView s = sessions.get(sessionId);
            StringBuilder sb = new StringBuilder();
            sb.append("会话 ").append(shortId(s.id()))
                    .append("\n状态: ").append(s.status());
            if (s.agentNodeId() != null) {
                sb.append(" · 节点: ").append(s.agentNodeId());
            }
            if (s.model() != null) {
                sb.append(" · 模型: ").append(s.model());
            }
            sb.append("\n任务: ").append(ToolText.oneLine(s.taskSpec(), 500));
            if (s.summary() != null && !s.summary().isBlank()) {
                sb.append("\n摘要: ").append(ToolText.truncate(s.summary(), 800));
            }
            if (s.inputTokens() != null || s.outputTokens() != null || s.costUsd() != null) {
                sb.append("\n用量: 输入=").append(zero(s.inputTokens()))
                        .append(" 输出=").append(zero(s.outputTokens()))
                        .append(" 缓存读=").append(zero(s.cacheReadTokens()))
                        .append(" 轮次=").append(zero(s.turnCount()));
                if (s.costUsd() != null) {
                    sb.append(" 成本=$").append(String.format("%.4f", s.costUsd()));
                }
            }
            sb.append("\n创建: ").append(ToolText.time(s.createdAt()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", s.id());
            data.put("status", s.status());
            data.put("projectId", s.projectId() == null ? "" : s.projectId());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> sessionProject(args));
    }

    @Bean
    public PlatformTool getSessionEventsTool() {
        ToolSpec spec = new ToolSpec("get_session_events", "查询会话最近事件",
                "查看会话最近的事件（状态变化/工具输出/错误），用于回答「卡在哪了」「为什么失败」。"
                        + "tail 默认 20，最大 100；每条内容会截断。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64},
                            "tail": {"type": "integer", "default": 20, "minimum": 1, "maximum": 100}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-session", M1);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String sessionId = ToolText.str(args, "sessionId");
            if (sessionId == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供会话 id");
            }
            int tail = ToolText.intArg(args, "tail", 20, 1, 100);
            List<SessionEvent> list = sessions.events(sessionId, 0, tail);
            if (list.isEmpty()) {
                return ToolResult.ok("该会话还没有事件。");
            }
            StringBuilder sb = new StringBuilder("最近 " + list.size() + " 条事件：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SessionEvent e : list) {
                String content = ToolText.oneLine(e.content(), 300);
                sb.append("\n[").append(e.type()).append("] ").append(content);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", e.seq());
                row.put("type", e.type());
                row.put("content", content);
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("events", rows));
        }, args -> sessionProject(args));
    }

    @Bean
    public PlatformTool startSessionTool() {
        ToolSpec spec = new ToolSpec("start_session", "启动 coding 会话",
                "在项目下创建并启动 coding 会话（会下发 runner、产生模型费用）。taskSpec 写清要做什么；"
                        + "可关联 requirementId/workItemId（也接受展示编号 REQ-<seq> / WI-<seq>）。"
                        + "调用后生成确认单，用户确认后才真正启动。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "taskSpec": {"type": "string", "maxLength": 20000},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "workItemId": {"type": "string", "maxLength": 64},
                            "agentNodeId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["taskSpec"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-session", M2);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            String taskSpec = ToolText.str(args, "taskSpec");
            if (projectId == null) {
                return projectRequired();
            }
            if (taskSpec == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供任务说明 taskSpec");
            }
            String requirementRef = ToolText.str(args, "requirementId");
            String workItemRef = ToolText.str(args, "workItemId");
            String requirementId = requirementRef == null ? null
                    : requirements.resolveRef(projectId, requirementRef, null).getId();
            String workItemId = workItemRef == null ? null
                    : workItems.resolveRef(projectId, workItemRef).getId();
            CreateSessionRequest req = new CreateSessionRequest(
                    null, null, projectId, workItemId,
                    requirementId, taskSpec, null, null, null,
                    ToolText.str(args, "agentNodeId"), null, null, null, null, null, null);
            SessionView view = sessions.create(req);
            String text = "已启动会话 " + shortId(view.id()) + "（状态 " + view.status() + "）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "sessionId", view.id(), "status", view.status(), "projectId", projectId));
        });
    }

    @Bean
    public PlatformTool sendSessionInputTool() {
        ToolSpec spec = new ToolSpec("send_session_input", "向会话发送输入",
                "把一段话注入运行中的 coding 会话，等于让 runner 的 agent 立即执行该指令（危险操作）。"
                        + "必须由用户文本短码确认，卡片不提供执行按钮；预览会展示完整输入文本。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64},
                            "text": {"type": "string", "maxLength": 20000}
                          },
                          "required": ["sessionId", "text"]
                        }
                        """,
                ToolRisk.DANGEROUS, "DEVELOPER", false, "devmind-session", M2);
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String sessionId = ToolText.str(args, "sessionId");
                String text = ToolText.str(args, "text");
                if (sessionId == null || text == null) {
                    return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供会话 id 与输入文本");
                }
                sessions.input(sessionId, text);
                String msg = "已向会话 " + shortId(sessionId) + " 发送输入";
                return ToolResult.ok(msg, msg, ToolResult.data("sessionId", sessionId));
            }

            /** DANGEROUS 预览：目标会话、节点与完整输入文本，不截断。 */
            @Override
            public String preview(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String sessionId = ToolText.str(args, "sessionId");
                String text = ToolText.str(args, "text");
                String node = "";
                String task = "";
                try {
                    SessionView s = sessions.get(sessionId);
                    if (s.agentNodeId() != null) {
                        node = s.agentNodeId();
                    }
                    task = ToolText.oneLine(s.taskSpec(), 80);
                } catch (RuntimeException ignored) {
                    // 预览阶段对象不存在不阻塞确认；执行时仍会报 NOT_FOUND
                }
                return "**向会话发送输入（危险操作）**\n"
                        + "- 会话：`" + sessionId + "`" + (task.isEmpty() ? "" : "（" + task + "）") + "\n"
                        + "- 执行节点：" + (node.isEmpty() ? "未知" : node) + "\n"
                        + "- 输入全文：\n" + text;
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return sessionProject(args);
            }
        };
    }

    @Bean
    public PlatformTool stopSessionTool() {
        ToolSpec spec = new ToolSpec("stop_session", "停止会话",
                "立即终止运行中的 coding 会话（等同 kill）。调用后生成确认单；用于用户明确要求停止时。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-session", M2);
        return PlatformTool.of(spec, (call, ctx) -> {
            String sessionId = ToolText.str(call.args(), "sessionId");
            if (sessionId == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供会话 id");
            }
            SessionView view = sessions.kill(sessionId);
            String text = "已停止会话 " + shortId(view.id()) + "（状态 " + view.status() + "）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "sessionId", view.id(), "status", view.status()));
        }, args -> sessionProject(args));
    }

    // ---------------- 小助手 ----------------

    private String sessionProject(Map<String, Object> args) {
        String sessionId = ToolText.str(args, "sessionId");
        if (sessionId == null) {
            return null;
        }
        try {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return sessions.get(sessionId).projectId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String projectId(Map<String, Object> args, ToolContext ctx) {
        String fromArgs = ToolText.str(args, "projectId");
        return fromArgs != null ? fromArgs
                : (ctx.projectId() == null || ctx.projectId().isBlank() ? null : ctx.projectId());
    }

    private static ToolResult projectRequired() {
        return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
    }

    private static String shortId(String id) {
        return id == null ? "-" : (id.length() <= 8 ? id : id.substring(0, 8));
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    private static long zero(Integer v) {
        return v == null ? 0 : v;
    }
}
