package com.devmind.knowledge.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
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
 * CAP-94 起检索范围一律显式绑定：kbIds 必填（可选，≤5）；未提供不检索，
 * 返回「请指定知识库」提示 + 当前可用库清单，由模型选库后重试。
 */
@Configuration
public class KnowledgeTools {

    private static final String M = "M1";
    private static final int MAX_KBS = 5;

    private final KnowledgeSearchService searchService;
    private final KnowledgeBaseRepository kbRepo;

    public KnowledgeTools(KnowledgeSearchService searchService, KnowledgeBaseRepository kbRepo) {
        this.searchService = searchService;
        this.kbRepo = kbRepo;
    }

    @Bean
    public PlatformTool searchKnowledgeTool() {
        ToolSpec spec = new ToolSpec("search_knowledge", "检索知识库",
                "按关键词检索指定知识库，返回文档摘要、命中片段与出处。回答规范/流程/历史经验类问题前先检索；"
                        + "query 必填，kbIds 指定要检索的知识库（≤5 个），不知道有哪些库时不传 kbIds 调用一次"
                        + "即可获取库清单；可用 docType/tag 缩小范围。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "maxLength": 200},
                            "kbIds": {
                              "type": "array",
                              "items": {"type": "integer"},
                              "maxItems": 5,
                              "description": "要检索的知识库 id 列表（≤5）；不传则不检索，返回可用库清单"
                            },
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
            List<Long> kbIds = kbIdsArg(args);
            if (kbIds == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "kbIds 元素必须是知识库 id（整数），且不超过 " + MAX_KBS + " 个");
            }
            if (kbIds.isEmpty()) {
                return noBindingResult();
            }
            String docType = ToolText.str(args, "docType");
            String tag = ToolText.str(args, "tag");
            KnowledgeSearchV2Response result = searchService.searchDocuments(new KnowledgeSearchV2Request(
                    kbIds, query, docType == null ? null : List.of(docType.toUpperCase()),
                    tag == null ? null : List.of(tag),
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

    /** kbIds 参数解析：缺失/空 = 空表（走库清单提示）；非整数或超限 = null（参数错误）。 */
    private static List<Long> kbIdsArg(Map<String, Object> args) {
        Object raw = args.get("kbIds");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > MAX_KBS) {
            return null;
        }
        List<Long> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Number n)) {
                return null;
            }
            long id = n.longValue();
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** CAP-94 FR-05：未绑库不检索，返回提示 + 当前 active 库清单（供模型选库重试）。 */
    private ToolResult noBindingResult() {
        List<KnowledgeBaseEntity> kbs = kbRepo.findByStatusOrderByCreatedAtDesc(KnowledgeBaseEntity.STATUS_ACTIVE);
        if (kbs.isEmpty()) {
            return ToolResult.ok("请先指定要检索的知识库（kbIds）。当前平台还没有任何启用中的知识库。");
        }
        StringBuilder sb = new StringBuilder("请先指定要检索的知识库（kbIds 参数）。当前可用的知识库：");
        List<Map<String, Object>> rows = new ArrayList<>(kbs.size());
        for (KnowledgeBaseEntity kb : kbs) {
            sb.append("\n- [").append(kb.getId()).append("] ").append(kb.getName());
            if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                sb.append("：").append(ToolText.oneLine(kb.getDescription(), 80));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", kb.getId());
            row.put("name", kb.getName());
            row.put("description", kb.getDescription());
            rows.add(row);
        }
        return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("knowledgeBases", rows));
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
