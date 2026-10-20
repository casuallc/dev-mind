package com.devmind.session.tool;

import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionManagerService;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-78 FR-04/05 会话生命周期与收口工具：finish/suspend/resume、会话级/需求级收口。
 *
 * <p>语义全部复用 {@link SessionManagerService}（与网页按钮同源），归属/状态机/互斥校验
 * 由 Service 既有逻辑把关，工具只做寻址解析与预览；异常经网关统一映射。</p>
 *
 * <p>寻址（FR-04）：{@code sessionId} 参数同时接受会话内部 id 与需求编号——
 * 传 {@code REQ-<seq>} 时解析为该需求最近活跃会话（无活跃取最近非终态，再无取最近一条，
 * 供 resume 终态会话使用；解析不到报错不猜，R2）。解析出的目标 sid 与状态写进预览与回执。</p>
 */
@Configuration
public class SessionLifecycleTools {

    private static final String SINCE = "CAP-78 M1";

    private final SessionManagerService sessions;
    private final SessionRepository sessionRepo;
    private final RequirementService requirements;

    public SessionLifecycleTools(SessionManagerService sessions, SessionRepository sessionRepo,
                                 RequirementService requirements) {
        this.sessions = sessions;
        this.sessionRepo = sessionRepo;
        this.requirements = requirements;
    }

    @Bean
    public PlatformTool finishSessionTool() {
        ToolSpec spec = new ToolSpec("finish_session", "优雅结束会话",
                "向运行中的 coding 会话发送优雅结束指令（关 stdin，agent 处理完当前轮后自然退出 → DONE/FAILED，"
                        + "工作区保留）。用户说「结束会话」默认用这个；要强杀（立即终止）用 stop_session。"
                        + "sessionId 支持会话 id 或需求编号 REQ-<seq>（解析为该需求最近活跃的会话）。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-session", SINCE);
        return lifecycleTool(spec, "finish",
                s -> "已向会话 " + shortId(s.getId()) + " 发送优雅结束指令：agent 处理完当前轮后自然退出"
                        + "（DONE/FAILED），工作区与改动保留。");
    }

    @Bean
    public PlatformTool suspendSessionTool() {
        ToolSpec spec = new ToolSpec("suspend_session", "挂起会话",
                "挂起运行中的 coding 会话：停止进程但保留工作区与对话历史，之后可用 resume_session 继续。"
                        + "sessionId 支持会话 id 或需求编号 REQ-<seq>（解析为该需求最近活跃的会话）。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-session", SINCE);
        return lifecycleTool(spec, "suspend",
                s -> "已挂起会话 " + shortId(s.getId()) + "（SUSPENDED）：进程已停止，"
                        + "工作区与对话历史保留，可用 resume_session 继续。");
    }

    @Bean
    public PlatformTool resumeSessionTool() {
        ToolSpec spec = new ToolSpec("resume_session", "继续会话",
                "继续已结束的 coding 会话（SUSPENDED/DONE/FAILED/TERMINATED → RUNNING，claude --resume "
                        + "续接对话历史）。若该会话/需求的工作区已收口，继续会重新打开工作区（状态置回 OPEN）。"
                        + "sessionId 支持会话 id 或需求编号 REQ-<seq>（解析为该需求最近的会话）。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-session", SINCE);
        return lifecycleTool(spec, "resume",
                s -> "已继续会话 " + shortId(s.getId()) + "（RUNNING）：claude --resume 续接对话历史。");
    }

    @Bean
    public PlatformTool finalizeSessionWorkspaceTool() {
        ToolSpec spec = new ToolSpec("finalize_session_workspace", "收口会话工作区",
                "收口单个会话的固定工作区（CAP-42）：feature 分支合并进基线（merge --ff）并 push，"
                        + "删除 worktree。会话仍在运行时不能收口（先 finish/stop）；有未提交改动时收口会失败并"
                        + "保留工作区（可 resume 提交后重试，或 discardChanges=true 丢弃改动重试）。"
                        + "sessionId 支持会话 id 或需求编号 REQ-<seq>。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": {"type": "string", "maxLength": 64},
                            "discardChanges": {"type": "boolean", "default": false},
                            "deleteRemoteBranch": {"type": "boolean", "default": true}
                          },
                          "required": ["sessionId"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-session", SINCE, 90);
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                SessionEntity s = resolveSession(args, ctx);
                boolean discard = boolArg(args, "discardChanges", false);
                boolean delRemote = boolArg(args, "deleteRemoteBranch", true);
                FinalizeResult r = sessions.finalizeWorkspace(s.getId(), discard, delRemote);
                String text = "会话 " + shortId(s.getId()) + " 工作区已收口：feature 分支已合并进基线并推送，"
                        + "worktree 已删除。"
                        + (r.detail() == null || r.detail().isBlank() ? ""
                                : "\n详情：" + ToolText.truncate(r.detail(), 500));
                return ToolResult.ok(text, text, ToolResult.data(
                        "sessionId", s.getId(), "finalized", true));
            }

            @Override
            public String preview(ToolCall call, ToolContext ctx) {
                return sessionPreview(call, ctx, "收口会话工作区",
                        "feature 分支合并进基线并 push，删除 worktree；"
                                + "工作区状态以执行时校验为准（有未提交改动将失败并保留工作区）");
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return sessionProject(args, ctx);
            }
        };
    }

    @Bean
    public PlatformTool finalizeRequirementWorkspaceTool() {
        ToolSpec spec = new ToolSpec("finalize_requirement_workspace", "收口需求工作区",
                "收口整个需求的工作区（CAP-51，等同网页需求详情页「收口」）：取该需求最近一个工作区 OPEN 的"
                        + "会话，feature/req-<id> 分支合并进基线（merge --ff）并 push、保留工作树；"
                        + "需求与同需求全部 OPEN 会话行置 FINALIZED。**会 push 共享基线分支，等同生产变更，"
                        + "必须用户文本短码确认**（确认卡无执行按钮）。有未提交改动时收口失败并保留工作区"
                        + "（可 resume 会话提交后重试，或 discardChanges=true 丢弃改动重试）。"
                        + "requirementId 支持 REQ-<seq>/内部 id/纯数字。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "discardChanges": {"type": "boolean", "default": false},
                            "deleteRemoteBranch": {"type": "boolean", "default": false}
                          },
                          "required": ["requirementId"]
                        }
                        """,
                ToolRisk.DANGEROUS, "DEVELOPER", true, "devmind-session", SINCE, 90);
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String pid = projectId(args, ctx);
                String ref = ToolText.str(args, "requirementId");
                if (pid == null) {
                    return projectRequired();
                }
                if (ref == null) {
                    return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                            "请提供需求编号 requirementId（如 REQ-3）");
                }
                RequirementEntity req = requirements.resolveRef(pid, ref, null);
                boolean discard = boolArg(args, "discardChanges", false);
                boolean delRemote = boolArg(args, "deleteRemoteBranch", false);
                FinalizeResult r = sessions.finalizeRequirementWorkspace(pid, req.getId(),
                        discard, delRemote);
                String text = "REQ-" + req.getSeq() + "（" + ToolText.oneLine(req.getTitle(), 60)
                        + "）工作区已收口：需求分支已合并进基线并推送，工作树保留（后续会话可继续）。"
                        + (r.detail() == null || r.detail().isBlank() ? ""
                                : "\n详情：" + ToolText.truncate(r.detail(), 500));
                return ToolResult.ok(text, text, ToolResult.data(
                        "requirementId", req.getId(), "requirementSeq", req.getSeq(),
                        "finalized", true));
            }

            /** FR-05：DANGEROUS 预览——需求编号/标题、基线分支、涉及仓库、discard 红字、脏工作区警示。 */
            @Override
            public String preview(ToolCall call, ToolContext ctx) {
                Map<String, Object> args = call.args();
                String pid = projectId(args, ctx);
                String ref = ToolText.str(args, "requirementId");
                if (pid == null || ref == null) {
                    return null;
                }
                try {
                    RequirementEntity req = requirements.resolveRef(pid, ref, null);
                    SessionEntity target = sessionRepo
                            .findByRequirementIdOrderByCreatedAtDesc(req.getId()).stream()
                            .filter(s -> SessionEntity.WORKSPACE_OPEN.equals(s.getWorkspaceState()))
                            .filter(s -> s.getAgentNodeId() != null && !s.getAgentNodeId().isBlank())
                            .findFirst().orElse(null);
                    StringBuilder sb = new StringBuilder("**收口需求工作区（危险操作：push 共享基线）**\n");
                    sb.append("- 需求：REQ-").append(req.getSeq())
                            .append("（").append(ToolText.oneLine(req.getTitle(), 60)).append("）\n");
                    if (target == null) {
                        sb.append("- 目标会话：未找到工作区 OPEN 的会话（执行时会报「该需求工作区未开启或已收口」）\n");
                    } else {
                        sb.append("- 目标会话：").append(shortId(target.getId()))
                                .append("（").append(target.getStatus()).append("）\n");
                        sb.append("- 基线分支：").append(target.getBaseBranch() == null
                                ? "未知" : target.getBaseBranch()).append("\n");
                        try {
                            List<String> repos = sessions.get(target.getId()).repoNames();
                            if (repos != null && !repos.isEmpty()) {
                                sb.append("- 涉及仓库：").append(String.join(", ", repos)).append("\n");
                            }
                        } catch (RuntimeException ignored) {
                            // 仓库名取不到不阻塞预览
                        }
                    }
                    sb.append("- 删除远端 feature 分支：")
                            .append(boolArg(args, "deleteRemoteBranch", false) ? "是" : "否（保留）").append("\n");
                    if (boolArg(args, "discardChanges", false)) {
                        sb.append("- 🔴 **将丢弃工作区全部未提交改动（不可恢复）**\n");
                    }
                    sb.append("- ⚠️ 工作区脏状态以执行时 runner 校验为准：存在未提交改动时收口将失败并"
                            + "保留工作区（可处理后重试）");
                    return sb.toString();
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return projectId(args, ctx);
            }
        };
    }

    // ---------------- finish/suspend/resume 共用骨架 ----------------

    /**
     * 生命周期三件套共用结构：解析目标会话（支持 REQ 编号）→ 预览点名 sid 与状态 →
     * 执行调 Service 同名方法 → 回执带 sid。预览注明收口重开语义（FR-04 resume/R5）。
     */
    private PlatformTool lifecycleTool(ToolSpec spec, String action,
                                       java.util.function.Function<SessionEntity, String> receipt) {
        return new PlatformTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                SessionEntity s = resolveSession(call.args(), ctx);
                switch (action) {
                    case "finish" -> sessions.finish(s.getId());
                    case "suspend" -> sessions.suspend(s.getId());
                    case "resume" -> sessions.resume(s.getId());
                    default -> throw new IllegalArgumentException("unknown action: " + action);
                }
                String text = receipt.apply(s);
                return ToolResult.ok(text, text, ToolResult.data(
                        "sessionId", s.getId(), "requirementId",
                        s.getRequirementId() == null ? "" : s.getRequirementId()));
            }

            /** FR-04/R2：REQ 编号解析出的目标会话 sid 与状态必须写进预览。 */
            @Override
            public String preview(ToolCall call, ToolContext ctx) {
                String actionCn = switch (action) {
                    case "finish" -> "优雅结束会话";
                    case "suspend" -> "挂起会话";
                    case "resume" -> "继续会话";
                    default -> action;
                };
                return sessionPreview(call, ctx, actionCn, null);
            }

            @Override
            public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
                return sessionProject(args, ctx);
            }
        };
    }

    private String sessionPreview(ToolCall call, ToolContext ctx, String title, String extraNote) {
        SessionEntity s;
        try {
            s = resolveSession(call.args(), ctx);
        } catch (RuntimeException e) {
            return null; // 预览解析不到不阻塞确认；execute 会报 NOT_FOUND
        }
        StringBuilder sb = new StringBuilder("**" + title + "**\n");
        sb.append("- 目标会话：").append(shortId(s.getId()))
                .append("（").append(s.getStatus()).append("）");
        if (s.getTaskSpec() != null && !s.getTaskSpec().isBlank()) {
            sb.append("\n- 任务：").append(ToolText.oneLine(s.getTaskSpec(), 80));
        }
        if (s.getAgentNodeId() != null) {
            sb.append("\n- 执行节点：").append(s.getAgentNodeId());
        }
        // R5：收口后继续会话会重新打开工作区，预览注明
        if (SessionEntity.WORKSPACE_FINALIZED.equals(s.getWorkspaceState())) {
            sb.append("\n- ⚠️ 该会话工作区已收口，继续会话将重新打开工作区（状态置回 OPEN）");
        }
        if (extraNote != null) {
            sb.append("\n- ").append(extraNote);
        }
        return sb.toString();
    }

    // ---------------- 小助手 ----------------

    /**
     * FR-04 寻址：会话内部 id 直接命中；否则按需求编号（REQ-<seq>/id/纯数字）解析——
     * 最近活跃会话优先，无活跃取最近非终态，再无取最近一条（resume 场景目标恒为终态会话，
     * 语义由 Service 校验兜底）；皆无 → NOT_FOUND，不猜。
     */
    private SessionEntity resolveSession(Map<String, Object> args, ToolContext ctx) {
        String ref = ToolText.str(args, "sessionId");
        if (ref == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "请提供会话 id 或需求编号（sessionId，如 REQ-3）");
        }
        var byId = sessionRepo.findById(ref);
        if (byId.isPresent()) {
            return byId.get();
        }
        RequirementEntity req;
        try {
            // 内部 id 直查无需项目；REQ-<seq> 缺项目时 resolveRef 报 BAD_REQUEST（需项目上下文）
            req = requirements.resolveRef(projectId(args, ctx), ref, null);
        } catch (DevMindException e) {
            // 需求侧 NOT_FOUND 换成会话语境文案；BAD_REQUEST（缺项目）原样透传
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "会话或需求不存在: " + ref);
            }
            throw e;
        } catch (RuntimeException e) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "会话或需求不存在: " + ref);
        }
        List<SessionEntity> list = sessionRepo.findByRequirementIdOrderByCreatedAtDesc(req.getId());
        if (list.isEmpty()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "该需求下没有会话: " + ref);
        }
        return list.stream().filter(s -> isActive(s.getStatus())).findFirst()
                .orElseGet(() -> list.stream().filter(s -> !isTerminal(s.getStatus())).findFirst()
                        .orElse(list.get(0)));
    }

    private String sessionProject(Map<String, Object> args, ToolContext ctx) {
        try {
            return resolveSession(args, ctx).getProjectId();
        } catch (RuntimeException e) {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return null;
        }
    }

    private static boolean isActive(String status) {
        try {
            return SessionState.valueOf(status).isActive();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTerminal(String status) {
        try {
            return !SessionState.valueOf(status).isActive()
                    && !SessionState.SUSPENDED.name().equals(status);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean def) {
        Object v = args == null ? null : args.get(key);
        return v instanceof Boolean b ? b : def;
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
}
