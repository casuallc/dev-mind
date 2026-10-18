package com.devmind.knowledge.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.EntryView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.6 知识库工具：全文检索（全局库 + 当前项目库）。
 * 只读；返回命中摘要，正文按条目 id 截断展示，避免把大段内容灌回模型。
 */
@Configuration
public class KnowledgeTools {

    private static final String M = "M1";

    private final KnowledgeBaseService knowledge;

    public KnowledgeTools(KnowledgeBaseService knowledge) {
        this.knowledge = knowledge;
    }

    @Bean
    public PlatformTool searchKnowledgeTool() {
        ToolSpec spec = new ToolSpec("search_knowledge", "检索知识库",
                "按关键词检索知识库（全局库 + 当前项目库），返回条目摘要与片段。"
                        + "回答规范/流程/历史经验类问题前先检索；query 必填。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "maxLength": 200},
                            "projectId": {"type": "string", "maxLength": 64},
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
            List<EntryView> list = knowledge.search(query, projectId)
                    .stream().limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("没有检索到相关知识条目。");
            }
            StringBuilder sb = new StringBuilder("检索到 " + list.size() + " 条知识：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (EntryView e : list) {
                sb.append("\n- [").append(e.id()).append("] ").append(e.name())
                        .append("（").append(e.scope()).append("）");
                if (e.tags() != null && !e.tags().isEmpty()) {
                    sb.append(" tags=").append(String.join(",", e.tags()));
                }
                sb.append("\n  ").append(ToolText.oneLine(e.contentMd(), 240));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.id());
                row.put("name", e.name());
                row.put("scope", e.scope());
                row.put("excerpt", ToolText.oneLine(e.contentMd(), 400));
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("entries", rows));
        });
    }
}
