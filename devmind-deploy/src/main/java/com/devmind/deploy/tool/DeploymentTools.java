package com.devmind.deploy.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.dto.StepView;
import com.devmind.deploy.service.DeploymentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.4 部署域工具：只读历史与详情。本期不提供部署/回滚写操作（§6.5 明确排除）。
 */
@Configuration
public class DeploymentTools {

    private static final String M = "M3";

    private final DeploymentService deployments;

    public DeploymentTools(DeploymentService deployments) {
        this.deployments = deployments;
    }

    @Bean
    public PlatformTool listDeploymentsTool() {
        ToolSpec spec = new ToolSpec("list_deployments", "查询部署记录",
                "列出项目最近的部署（id/环境/状态/关联工作项/时间）。status 可过滤 SUCCESS/FAILED/RUNNING 等。",
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
                ToolRisk.READ, "VIEWER", true, "devmind-deploy", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            if (projectId == null) {
                return projectRequired();
            }
            var page = deployments.history(projectId, ToolText.str(args, "status"), 0,
                    ToolText.limit(args));
            List<DeploymentView> list = page.items();
            if (list.isEmpty()) {
                return ToolResult.ok("该项目下没有匹配的部署记录。");
            }
            StringBuilder sb = new StringBuilder("共 " + page.total() + " 条部署记录（显示 "
                    + list.size() + " 条）：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DeploymentView d : list) {
                sb.append("\n- #").append(d.id()).append(" [").append(d.status()).append("] ")
                        .append(d.env() == null ? "-" : d.env());
                if (d.workItem() != null) {
                    sb.append(" · ").append(d.workItem().code());
                }
                sb.append(" · ").append(ToolText.time(d.createdAt()));
                if (d.errorSummary() != null && !d.errorSummary().isBlank()) {
                    sb.append("\n  失败: ").append(ToolText.oneLine(d.errorSummary(), 120));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deploymentId", d.id());
                row.put("status", d.status());
                row.put("env", d.env() == null ? "" : d.env());
                row.put("createdAt", ToolText.time(d.createdAt()));
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("deployments", rows));
        });
    }

    @Bean
    public PlatformTool getDeploymentTool() {
        ToolSpec spec = new ToolSpec("get_deployment", "查询部署详情",
                "查询部署单详情（环境/状态/步骤进度/失败原因/日志尾部）。用于回答「部署到哪一步了」。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "deploymentId": {"type": "integer", "minimum": 1},
                            "logLines": {"type": "integer", "default": 40, "minimum": 0, "maximum": 200}
                          },
                          "required": ["deploymentId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-deploy", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            if (!(args.get("deploymentId") instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供部署 id");
            }
            long id = n.longValue();
            DeploymentView d = deployments.get(id);
            StringBuilder sb = new StringBuilder();
            sb.append("部署 #").append(d.id()).append(" [").append(d.status()).append("]")
                    .append("\n环境: ").append(d.env() == null ? "-" : d.env())
                    .append(" · 构建: ").append(d.buildId() == null ? "-" : "#" + d.buildId());
            if (d.agentNodeId() != null) {
                sb.append(" · 节点: ").append(d.agentNodeId());
            }
            if (d.workItem() != null) {
                sb.append("\n工作项: ").append(d.workItem().code()).append(" ").append(d.workItem().title());
            }
            sb.append("\n创建: ").append(ToolText.time(d.createdAt()))
                    .append(" · 开始: ").append(ToolText.time(d.startedAt()))
                    .append(" · 结束: ").append(ToolText.time(d.finishedAt()));
            if (d.errorSummary() != null && !d.errorSummary().isBlank()) {
                sb.append("\n失败摘要: ").append(ToolText.truncate(d.errorSummary(), 600));
            }
            if (d.steps() != null && !d.steps().isEmpty()) {
                sb.append("\n步骤:");
                for (StepView s : d.steps()) {
                    sb.append("\n- ").append(s.seq()).append(". ").append(s.name())
                            .append(" [").append(s.status()).append("]");
                    if (s.detail() != null && !s.detail().isBlank()) {
                        sb.append(" ").append(ToolText.oneLine(s.detail(), 160));
                    }
                }
            }
            int lines = ToolText.intArg(args, "logLines", 40, 0, 200);
            if (lines > 0) {
                String logs = deployments.logs(id);
                if (logs != null && !logs.isBlank()) {
                    sb.append("\n日志尾部:\n").append(tail(logs, lines));
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deploymentId", d.id());
            data.put("status", d.status());
            data.put("projectId", d.projectId());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> deploymentProject(args));
    }

    // ---------------- 小助手 ----------------

    private String deploymentProject(Map<String, Object> args) {
        if (!(args.get("deploymentId") instanceof Number n)) {
            return null;
        }
        try {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return deployments.get(n.longValue()).projectId();
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
