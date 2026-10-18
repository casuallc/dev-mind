package com.devmind.release.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.project.dto.WorkItemBrief;
import com.devmind.release.dto.IncludedRequirement;
import com.devmind.release.dto.ReleaseView;
import com.devmind.release.service.ReleaseService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.4 发版域工具：只读历史与详情。发版写操作不在本期范围。
 */
@Configuration
public class ReleaseTools {

    private static final String M = "M3";

    private final ReleaseService releases;

    public ReleaseTools(ReleaseService releases) {
        this.releases = releases;
    }

    @Bean
    public PlatformTool listReleasesTool() {
        ToolSpec spec = new ToolSpec("list_releases", "查询发版记录",
                "列出发版记录（id/版本/状态/tag/关联工作项）。status 可过滤 SUCCESS/FAILED/RUNNING 等。",
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
                ToolRisk.READ, "VIEWER", true, "devmind-release", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            if (projectId == null) {
                return projectRequired();
            }
            List<ReleaseView> list = releases.history(projectId, ToolText.str(args, "status"))
                    .stream().limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("该项目下没有匹配的发版记录。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 条发版记录：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ReleaseView r : list) {
                sb.append("\n- #").append(r.id()).append(" ").append(r.version())
                        .append(" [").append(r.status()).append("]");
                if (r.tagName() != null) {
                    sb.append(" tag=").append(r.tagName());
                }
                sb.append(" · ").append(ToolText.time(r.createdAt()));
                if (r.errorSummary() != null && !r.errorSummary().isBlank()) {
                    sb.append("\n  失败: ").append(ToolText.oneLine(r.errorSummary(), 120));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("releaseId", r.id());
                row.put("version", r.version());
                row.put("status", r.status());
                row.put("createdAt", ToolText.time(r.createdAt()));
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("releases", rows));
        });
    }

    @Bean
    public PlatformTool getReleaseTool() {
        ToolSpec spec = new ToolSpec("get_release", "查询发版详情",
                "查询单次发版详情（版本/状态/制品/tag/归集的工作项与需求/失败原因）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "releaseId": {"type": "integer", "minimum": 1}
                          },
                          "required": ["releaseId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-release", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            if (!(args.get("releaseId") instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供发版 id");
            }
            long id = n.longValue();
            ReleaseView r = releases.get(id);
            StringBuilder sb = new StringBuilder();
            sb.append("发版 #").append(r.id()).append(" ").append(r.version())
                    .append(" [").append(r.status()).append("]");
            if (r.tagName() != null) {
                sb.append("\ntag: ").append(r.tagName());
            }
            if (r.artifactRef() != null) {
                sb.append("\n制品: ").append(ToolText.truncate(r.artifactRef(), 300));
            }
            if (r.workItem() != null) {
                sb.append("\n工作项: ").append(r.workItem().code()).append(" ").append(r.workItem().title());
            }
            if (r.errorSummary() != null && !r.errorSummary().isBlank()) {
                sb.append("\n失败摘要: ").append(ToolText.truncate(r.errorSummary(), 600));
            }
            if (r.includedWorkItems() != null && !r.includedWorkItems().isEmpty()) {
                sb.append("\n归集工作项 (").append(r.includedWorkItems().size()).append("):");
                for (WorkItemBrief w : r.includedWorkItems()) {
                    sb.append("\n- ").append(w.code()).append(" ").append(w.title());
                }
            }
            if (r.includedRequirements() != null && !r.includedRequirements().isEmpty()) {
                sb.append("\n归集需求 (").append(r.includedRequirements().size()).append("):");
                for (IncludedRequirement q : r.includedRequirements()) {
                    sb.append("\n- ").append(q.code()).append(" [").append(q.status()).append("] ")
                            .append(q.title());
                }
            }
            sb.append("\n开始: ").append(ToolText.time(r.startedAt()))
                    .append(" · 结束: ").append(ToolText.time(r.finishedAt()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("releaseId", r.id());
            data.put("version", r.version());
            data.put("status", r.status());
            data.put("projectId", r.projectId());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> releaseProject(args));
    }

    // ---------------- 小助手 ----------------

    private String releaseProject(Map<String, Object> args) {
        if (!(args.get("releaseId") instanceof Number n)) {
            return null;
        }
        try {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return releases.get(n.longValue()).projectId();
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
}
