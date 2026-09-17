package com.devmind.integration.connector.feishu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * 飞书 docx blocks → Markdown 转换器（CAP-45 FR-02）。
 * 覆盖：page（标题）、heading1~9、text、bullet、ordered（连续编号）、code、quote、
 * quote_container、todo、divider、table（pipe 表）；text_run 支持 bold/italic/
 * strikethrough/inline_code/link。未知块降级提取纯文本；拿不到结构的直接跳过，不炸。
 * 已知保真损失（可接受）：嵌套列表不缩进（拍平一级）、表格单元格多段落以 <br> 连接。
 */
public final class DocxMarkdownConverter {

    private DocxMarkdownConverter() {
    }

    // block_type 常量（飞书 docx 协议）
    private static final int PAGE = 1;
    private static final int TEXT = 2;
    private static final int HEADING1 = 3; // 3..11 = heading1..9
    private static final int BULLET = 12;
    private static final int ORDERED = 13;
    private static final int CODE = 14;
    private static final int QUOTE = 15;
    private static final int TODO = 17;
    private static final int DIVIDER = 22;
    private static final int IMAGE = 27;
    private static final int TABLE = 31;
    private static final int TABLE_CELL = 32;
    private static final int QUOTE_CONTAINER = 34;

    /** 类型 → 承载 elements 的载荷键名（text/heading/bullet/ordered/quote/todo/code 同形） */
    private static String payloadKey(int blockType) {
        if (blockType == TEXT) return "text";
        if (blockType >= HEADING1 && blockType <= 11) return "heading" + (blockType - HEADING1 + 1);
        if (blockType == BULLET) return "bullet";
        if (blockType == ORDERED) return "ordered";
        if (blockType == CODE) return "code";
        if (blockType == QUOTE) return "quote";
        if (blockType == TODO) return "todo";
        return null;
    }

    public static String convert(List<JsonNode> blocks, String pageTitle) {
        Map<String, JsonNode> byId = blocks.stream()
                .filter(b -> b.hasNonNull("block_id"))
                .collect(Collectors.toMap(b -> b.get("block_id").asText(), Function.identity(), (a, b) -> a));
        Set<String> tableSubtree = new HashSet<>();
        Set<String> quoted = new HashSet<>();
        for (JsonNode b : blocks) {
            int type = b.path("block_type").asInt();
            if (type == TABLE) {
                b.path("table").path("cells").forEach(cell -> collectDescendants(cell.asText(), byId, tableSubtree));
            } else if (type == QUOTE_CONTAINER) {
                b.path("children").forEach(c -> quoted.add(c.asText()));
            }
        }

        StringBuilder out = new StringBuilder();
        if (pageTitle != null && !pageTitle.isBlank()) {
            out.append("# ").append(pageTitle.trim()).append("\n\n");
        }
        int orderedCounter = 0;
        for (JsonNode b : blocks) {
            String id = b.path("block_id").asText();
            int type = b.path("block_type").asInt();
            if (type == PAGE || type == TABLE_CELL || tableSubtree.contains(id)) {
                continue; // page 只取标题；表格子树由 table 块统一渲染
            }
            if (type != ORDERED) {
                orderedCounter = 0;
            }
            String quotePrefix = quoted.contains(id) ? "> " : "";
            String payloadKey = payloadKey(type);
            switch (type) {
                case TEXT -> out.append(quotePrefix).append(elementsText(b.path(payloadKey))).append("\n\n");
                case BULLET -> out.append(quotePrefix).append("- ").append(elementsText(b.path(payloadKey))).append('\n');
                case ORDERED -> out.append(quotePrefix).append(++orderedCounter).append(". ")
                        .append(elementsText(b.path(payloadKey))).append('\n');
                case CODE -> out.append("```\n").append(elementsText(b.path(payloadKey))).append("\n```\n\n");
                case QUOTE -> out.append("> ").append(elementsText(b.path(payloadKey))).append("\n\n");
                case TODO -> out.append(quotePrefix)
                        .append(b.path(payloadKey).path("style").path("done").asBoolean(false) ? "- [x] " : "- [ ] ")
                        .append(elementsText(b.path(payloadKey))).append('\n');
                case DIVIDER -> out.append("---\n\n");
                case IMAGE -> out.append("![图片]\n\n");
                case TABLE -> out.append(renderTable(b, byId)).append('\n');
                case QUOTE_CONTAINER -> { /* 子块在主循环内带 "> " 前缀渲染 */ }
                default -> {
                    if (type >= HEADING1 && type <= 11) {
                        int level = Math.min(6, type - HEADING1 + 1);
                        out.append(quotePrefix).append("#".repeat(level)).append(' ')
                                .append(elementsText(b.path(payloadKey))).append("\n\n");
                    } else if (payloadKey != null) {
                        out.append(quotePrefix).append(elementsText(b.path(payloadKey))).append("\n\n");
                    }
                    // 其余未知块（网格/视图/附件卡片等）无 elements 可提取，跳过
                }
            }
        }
        return out.toString().trim() + '\n';
    }

    private static void collectDescendants(String blockId, Map<String, JsonNode> byId, Set<String> out) {
        if (!out.add(blockId)) {
            return;
        }
        JsonNode b = byId.get(blockId);
        if (b != null) {
            b.path("children").forEach(c -> collectDescendants(c.asText(), byId, out));
        }
    }

    /** table 块 → pipe 表格；cells 按 row_size×column_size 行主序；首行视作表头 */
    private static String renderTable(JsonNode tableBlock, Map<String, JsonNode> byId) {
        JsonNode table = tableBlock.path("table");
        JsonNode property = table.path("property");
        int rows = property.path("row_size").asInt(0);
        int cols = property.path("column_size").asInt(0);
        List<String> cells = new ArrayList<>();
        table.path("cells").forEach(c -> cells.add(c.asText()));
        if (rows <= 0 || cols <= 0 || cells.size() < (long) rows * cols) {
            return ""; // 结构不齐，降级不渲染（cell 文本仍会被子树跳过……此处保守返回空）
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(cellText(cells.get(r * cols + c), byId)).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append("| --- ".repeat(cols)).append("|\n");
            }
        }
        return sb.toString();
    }

    /** 单元格内各段落块文本，多段落以 <br> 连接，管道符转义 */
    private static String cellText(String cellId, Map<String, JsonNode> byId) {
        JsonNode cell = byId.get(cellId);
        if (cell == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        cell.path("children").forEach(childId -> {
            JsonNode child = byId.get(childId.asText());
            if (child != null) {
                String key = payloadKey(child.path("block_type").asInt());
                if (key != null) {
                    parts.add(elementsText(child.path(key)));
                }
            }
        });
        return String.join("<br>", parts).replace("|", "\\|");
    }

    /** text_run 行内元素串接（bold/italic/strikethrough/inline_code/link）；mention/equation 降级 */
    private static String elementsText(JsonNode container) {
        StringBuilder sb = new StringBuilder();
        container.path("elements").forEach(el -> {
            JsonNode run = el.path("text_run");
            if (!run.isMissingNode() && !run.isNull()) {
                String text = run.path("content").asText("");
                JsonNode style = run.path("text_element_style");
                if (style.path("inline_code").asBoolean(false)) text = "`" + text + "`";
                if (style.path("bold").asBoolean(false)) text = "**" + text + "**";
                if (style.path("italic").asBoolean(false)) text = "*" + text + "*";
                if (style.path("strikethrough").asBoolean(false)) text = "~~" + text + "~~";
                String url = style.path("link").path("url").asText("");
                if (!url.isEmpty()) text = "[" + text + "](" + url + ")";
                sb.append(text);
            } else if (el.has("mention_doc")) {
                sb.append(el.path("mention_doc").path("title").asText("文档"));
            } else if (el.has("equation")) {
                sb.append('$').append(el.path("equation").path("content").asText("").trim()).append('$');
            }
        });
        return sb.toString();
    }
}
