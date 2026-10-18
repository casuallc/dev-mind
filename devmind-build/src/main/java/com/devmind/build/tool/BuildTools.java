package com.devmind.build.tool;

import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.service.BuildService;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.3 构建域工具：构建历史/详情/触发。
 * 触发构建会占用执行节点并产出制品，属 WRITE（确认单确认后执行）。
 */
@Configuration
public class BuildTools {

    private static final String M = "M3";

    private final BuildService builds;

    public BuildTools(BuildService builds) {
        this.builds = builds;
    }

    @Bean
    public PlatformTool listBuildsTool() {
        ToolSpec spec = new ToolSpec("list_builds", "查询构建记录",
                "列出项目最近的构建（id/分支/commit/状态/耗时/关联工作项）。status 可过滤 "
                        + "SUCCESS/FAILED/RUNNING 等；未指定 projectId 用当前绑定项目。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "status": {"type": "string", "maxLength": 32},
                            "limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-build", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            if (projectId == null) {
                return projectRequired();
            }
            List<BuildView> list = builds.history(projectId, ToolText.str(args, "status"))
                    .stream().limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("该项目下没有匹配的构建记录。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 条构建记录：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BuildView b : list) {
                sb.append("\n- #").append(b.id()).append(" [").append(b.status()).append("] ")
                        .append(b.branch() == null ? "-" : b.branch());
                if (b.commit() != null) {
                    sb.append(" @").append(shortCommit(b.commit()));
                }
                if (b.workItem() != null) {
                    sb.append(" · ").append(b.workItem().code());
                }
                sb.append(" · ").append(ToolText.time(b.createdAt()));
                if (b.errorSummary() != null && !b.errorSummary().isBlank()) {
                    sb.append("\n  失败: ").append(ToolText.oneLine(b.errorSummary(), 120));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("buildId", b.id());
                row.put("status", b.status());
                row.put("branch", b.branch() == null ? "" : b.branch());
                row.put("commit", b.commit() == null ? "" : b.commit());
                row.put("createdAt", ToolText.time(b.createdAt()));
                if (b.workItemId() != null) {
                    row.put("workItemId", b.workItemId());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("builds", rows));
        });
    }

    @Bean
    public PlatformTool getBuildTool() {
        ToolSpec spec = new ToolSpec("get_build", "查询构建详情",
                "查询单个构建的详情与日志尾部（默认 40 行，最多 200 行）。用于定位构建失败原因。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "buildId": {"type": "integer", "minimum": 1},
                            "logTail": {"type": "integer", "default": 40, "minimum": 1, "maximum": 200}
                          },
                          "required": ["buildId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-build", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            if (!(args.get("buildId") instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供构建 id");
            }
            long id = n.longValue();
            BuildView b = builds.get(id);
            StringBuilder sb = new StringBuilder();
            sb.append("构建 #").append(b.id()).append(" [").append(b.status()).append("]")
                    .append("\n分支: ").append(b.branch() == null ? "-" : b.branch())
                    .append(" · 执行体: ").append(b.executor() == null ? "-" : b.executor());
            if (b.commit() != null) {
                sb.append("\ncommit: ").append(b.commit());
            }
            if (b.workItem() != null) {
                sb.append("\n工作项: ").append(b.workItem().code()).append(" ").append(b.workItem().title());
            }
            if (b.exitCode() != null) {
                sb.append("\nexit code: ").append(b.exitCode());
            }
            if (b.errorSummary() != null && !b.errorSummary().isBlank()) {
                sb.append("\n失败摘要: ").append(ToolText.truncate(b.errorSummary(), 600));
            }
            sb.append("\n开始: ").append(ToolText.time(b.startedAt()))
                    .append(" · 结束: ").append(ToolText.time(b.finishedAt()));
            int lines = ToolText.intArg(args, "logTail", 40, 1, 200);
            String logs = builds.logs(id);
            if (logs != null && !logs.isBlank()) {
                sb.append("\n日志尾部:\n").append(tail(logs, lines));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("buildId", b.id());
            data.put("status", b.status());
            data.put("projectId", b.projectId());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> buildProject(args));
    }

    @Bean
    public PlatformTool triggerBuildTool() {
        ToolSpec spec = new ToolSpec("trigger_build", "触发构建",
                "按项目默认构建配置触发一次构建（会占用执行节点并产出制品）。branch/commit 可空（取项目默认与当前 HEAD）；"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "branch": {"type": "string", "maxLength": 200},
                            "commit": {"type": "string", "maxLength": 64},
                            "workItemId": {"type": "string", "maxLength": 64},
                            "executor": {"type": "string", "enum": ["LOCAL", "AGENT"]}
                          }
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-build", M, 90);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            if (projectId == null) {
                return projectRequired();
            }
            BuildView view = builds.trigger(projectId, new TriggerRequest(
                    ToolText.str(args, "commit"), ToolText.str(args, "branch"),
                    ToolText.str(args, "executor"), null, null, ToolText.str(args, "workItemId")));
            String text = "已触发构建 #" + view.id() + "（状态 " + view.status() + "）";
            return ToolResult.ok(text, text, ToolResult.data(
                    "buildId", view.id(), "status", view.status(), "projectId", projectId));
        });
    }

    // ---------------- 小助手 ----------------

    private String buildProject(Map<String, Object> args) {
        if (!(args.get("buildId") instanceof Number n)) {
            return null;
        }
        try {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return builds.get(n.longValue()).projectId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String projectId(Map<String, Object> args, com.devmind.common.agent.tool.ToolContext ctx) {
        String fromArgs = ToolText.str(args, "projectId");
        return fromArgs != null ? fromArgs
                : (ctx.projectId() == null || ctx.projectId().isBlank() ? null : ctx.projectId());
    }

    private static ToolResult projectRequired() {
        return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
    }

    private static String shortCommit(String commit) {
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\\R");
        int from = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(all[i]);
        }
        return sb.toString();
    }
}
