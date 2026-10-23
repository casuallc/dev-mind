package com.devmind.knowledge.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.retrieve.KnowledgeSearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.6 / CAP-84 FR-14 知识库工具：Lucene BM25 + 向量 RRF 混合检索。
 * 检索范围为全局库 + 当前项目库；返回文档级摘要与高亮片段，正文按片段截断，避免灌爆模型上下文。
 */
@Configuration
public class KnowledgeTools {

    private static final String M = "M1";

    private final KnowledgeSearchService searchService;

    public KnowledgeTools(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Bean
    public PlatformTool searchKnowledgeTool() {
        ToolSpec spec = new ToolSpec("search_knowledge", "检索知识库",
                "按关键词检索知识库（全局库 + 当前项目库），返回文档摘要、命中片段与出处。"
                        + "回答规范/流程/历史经验类问题前先检索；query 必填，可用 docType/tag 缩小范围。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "maxLength": 200},
                            "projectId": {"type": "string", "maxLength": 64},
                            "docType": {
                              "type": "string",
                              "enum": ["TECH", "MAINT", "FAQ", "ARTIFACT"],
                              "description": "文档类型：技术文档/维护文档/常见问题/项目产物"
                            },
                            "tag": {"type": "string", "maxLength": 64},
                            "limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 30}
                          },
                          "required": ["query"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-knowledge", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String query = ToolText.str(args, "query");
            if (query == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供检索关键词");
            }
            String projectId = ToolText.str(args, "projectId");
            if (projectId == null && ctx.projectId() != null && !ctx.projectId().isBlank()) {
                projectId = ctx.projectId();
            }
            List<Long> kbIds = searchService.scopedKbIds(projectId);
            if (kbIds.isEmpty()) {
                return ToolResult.ok("没有检索到相关知识条目。");
            }
            String docType = ToolText.str(args, "docType");
            String tag = ToolText.str(args, "tag");
            KnowledgeSearchV2Response result = searchService.searchDocuments(new KnowledgeSearchV2Request(
                    kbIds, query, docType == null ? null : List.of(docType.toUpperCase()),
                    null, tag == null ? null : List.of(tag),
                    ToolText.intArg(args, "limit", 10, 1, 30)));
            if (result.hits().isEmpty()) {
                return ToolResult.ok("没有检索到相关知识条目。");
            }
            StringBuilder sb = new StringBuilder("检索到 " + result.hits().size() + " 篇知识：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (KnowledgeSearchV2Response.Hit hit : result.hits()) {
                sb.append("\n- [").append(hit.entryId()).append("] ").append(hit.title())
                        .append("（").append(hit.docType());
                if (hit.kbName() != null) {
                    sb.append("，").append(hit.kbName());
                }
                sb.append("）");
                if (hit.tags() != null && !hit.tags().isEmpty()) {
                    sb.append(" tags=").append(String.join(",", hit.tags()));
                }
                if (hit.path() != null && !hit.path().isBlank()) {
                    sb.append("\n  路径: ").append(hit.path());
                }
                String snippet = plainSnippet(hit.snippet(), 400);
                if (!snippet.isEmpty()) {
                    sb.append("\n  ").append(snippet);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", hit.entryId());
                row.put("name", hit.title());
                row.put("docType", hit.docType());
                row.put("kb", hit.kbName());
                row.put("path", hit.path());
                row.put("sourceUri", hit.sourceUri());
                row.put("tags", hit.tags());
                row.put("snippet", snippet);
                row.put("score", Math.round(hit.score() * 10000) / 10000.0);
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("entries", rows));
        });
    }

    /** 检索片段是 HTML 高亮，工具输出要纯文本：去掉 mark 标签、还原常见实体。 */
    private static String plainSnippet(String snippet, int max) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        String text = snippet.replace("<mark>", "").replace("</mark>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        return ToolText.oneLine(Objects.requireNonNullElse(text, ""), max);
    }
}
