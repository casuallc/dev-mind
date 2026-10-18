package com.devmind.docs.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.6 文档库工具：按关键词检索文档、读取正文。
 * DocumentService 的检索跨全库，工具层按项目过滤（给定 projectId 时）。
 */
@Configuration
public class DocumentTools {

    private static final String M = "M1";

    private final DocumentService documents;

    public DocumentTools(DocumentService documents) {
        this.documents = documents;
    }

    @Bean
    public PlatformTool listDocumentsTool() {
        ToolSpec spec = new ToolSpec("list_documents", "检索文档",
                "按关键词检索文档库（标题/正文/标签），返回文档 id、标题、类型与版本。"
                        + "给定 projectId 时只返回该项目文档；不给 keyword 时列出项目最近文档。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "maxLength": 200},
                            "keyword": {"type": "string", "maxLength": 200},
                            "projectId": {"type": "string", "maxLength": 64},
                            "limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-docs", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            // 兼容 query：文档要求参数名为 keyword，模型偶发用 query 时同样接受
            String keyword = ToolText.str(args, "keyword");
            if (keyword == null) {
                keyword = ToolText.str(args, "query");
            }
            String projectId = ToolText.str(args, "projectId");
            if (projectId == null && ctx.projectId() != null && !ctx.projectId().isBlank()) {
                projectId = ctx.projectId();
            }
            String pid = projectId;
            List<DocView> base = keyword == null
                    ? documents.list(null, pid, null, null)
                    : documents.search(keyword).stream()
                            .filter(d -> pid == null || pid.equals(d.projectId()))
                            .toList();
            List<DocView> list = base.stream().limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("没有检索到相关文档。");
            }
            StringBuilder sb = new StringBuilder("检索到 " + list.size() + " 篇文档：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DocView d : list) {
                sb.append("\n- [").append(d.id()).append("] ").append(d.title())
                        .append("（").append(d.kind()).append(" v").append(d.currentVersion()).append("）");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.id());
                row.put("title", d.title());
                row.put("kind", d.kind());
                row.put("version", d.currentVersion());
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("documents", rows));
        });
    }

    @Bean
    public PlatformTool getDocumentTool() {
        ToolSpec spec = new ToolSpec("get_document", "读取文档正文",
                "读取文档正文（默认当前版本，可指定 version）。长文档会截断展示。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "docId": {"type": "integer", "minimum": 1},
                            "version": {"type": "integer", "minimum": 1}
                          },
                          "required": ["docId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-docs", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            if (!(args.get("docId") instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供文档 id");
            }
            Integer version = args.get("version") instanceof Number v ? v.intValue() : null;
            DocDetail d = documents.get(n.longValue(), version);
            StringBuilder sb = new StringBuilder();
            sb.append(d.title()).append("（").append(d.kind()).append(" v").append(d.versionNo())
                    .append("，").append(d.status()).append("）");
            if (d.tags() != null && !d.tags().isEmpty()) {
                sb.append("\ntags: ").append(String.join(", ", d.tags()));
            }
            sb.append("\n\n").append(ToolText.truncate(d.contentMd(), 6000));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", d.id());
            data.put("title", d.title());
            data.put("kind", d.kind());
            data.put("version", d.versionNo());
            data.put("projectId", d.projectId() == null ? "" : d.projectId());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> documentProject(args));
    }

    // ---------------- 小助手 ----------------

    private String documentProject(Map<String, Object> args) {
        if (!(args.get("docId") instanceof Number n)) {
            return null;
        }
        try {
            // 网关在 execute 之前调用；对象不存在时返回 null，由 execute 报 NOT_FOUND
            return documents.get(n.longValue(), null).projectId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
