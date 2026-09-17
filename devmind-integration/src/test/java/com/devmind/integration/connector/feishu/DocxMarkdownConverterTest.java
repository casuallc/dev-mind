package com.devmind.integration.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CAP-45 FR-02：docx blocks → markdown 转换覆盖（标题/列表/代码/引用/表格/行内样式/未知块降级） */
class DocxMarkdownConverterTest {

    private static void assertContains(String md, String fragment) {
        assertTrue(md.contains(fragment), "期望包含 [" + fragment + "]，实际：\n" + md);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode block(String json) {
        return mapper.readTree(json);
    }

    private JsonNode textBlock(String id, int type, String payloadKey, String elementsJson) {
        return block("{\"block_id\":\"" + id + "\",\"block_type\":" + type + ",\"" + payloadKey
                + "\":{\"elements\":" + elementsJson + "}}");
    }

    private String textRun(String content) {
        return "[{\"text_run\":{\"content\":\"" + content + "\",\"text_element_style\":{}}}]";
    }

    @Test
    void headingsParagraphAndInlineStyles() {
        List<JsonNode> blocks = List.of(
                textBlock("b1", 3, "heading1", textRun("标题一")),
                textBlock("b2", 2, "text",
                        "[{\"text_run\":{\"content\":\"加粗\",\"text_element_style\":{\"bold\":true}}},"
                                + "{\"text_run\":{\"content\":\"斜体\",\"text_element_style\":{\"italic\":true}}},"
                                + "{\"text_run\":{\"content\":\"删除\",\"text_element_style\":{\"strikethrough\":true}}},"
                                + "{\"text_run\":{\"content\":\"code\",\"text_element_style\":{\"inline_code\":true}}},"
                                + "{\"text_run\":{\"content\":\"链接\",\"text_element_style\":{\"link\":{\"url\":\"https://a.b/c\"}}}}]"));

        String md = DocxMarkdownConverter.convert(blocks, null);

        assertContains(md, "# 标题一");
        assertContains(md, "**加粗**");
        assertContains(md, "*斜体*");
        assertContains(md, "~~删除~~");
        assertContains(md, "`code`");
        assertContains(md, "[链接](https://a.b/c)");
    }

    @Test
    void listsOrderedAndTodo() {
        List<JsonNode> blocks = List.of(
                textBlock("b1", 12, "bullet", textRun("无序一")),
                textBlock("b2", 12, "bullet", textRun("无序二")),
                textBlock("b3", 13, "ordered", textRun("有序一")),
                textBlock("b4", 13, "ordered", textRun("有序二")),
                textBlock("b5", 2, "text", textRun("打断计数")),
                textBlock("b6", 13, "ordered", textRun("重新计数")),
                block("{\"block_id\":\"b7\",\"block_type\":17,\"todo\":{\"elements\":"
                        + textRun("已完成事项") + ",\"style\":{\"done\":true}}}"));

        String md = DocxMarkdownConverter.convert(blocks, null);

        assertContains(md, "- 无序一\n- 无序二");
        assertContains(md, "1. 有序一\n2. 有序二");
        assertContains(md, "打断计数\n\n1. 重新计数");
        assertContains(md, "- [x] 已完成事项");
    }

    @Test
    void codeQuoteDividerAndQuoteContainer() {
        List<JsonNode> blocks = List.of(
                textBlock("b1", 14, "code", textRun("int a = 1;")),
                textBlock("b2", 15, "quote", textRun("引用一句")),
                block("{\"block_id\":\"b3\",\"block_type\":22}"),
                block("{\"block_id\":\"qc\",\"block_type\":34,\"children\":[\"qc1\"]}"),
                textBlock("qc1", 2, "text", textRun("容器内段落")));

        String md = DocxMarkdownConverter.convert(blocks, null);

        assertContains(md, "```\nint a = 1;\n```");
        assertContains(md, "> 引用一句");
        assertContains(md, "---");
        assertContains(md, "> 容器内段落");
    }

    @Test
    void tableRendersPipeWithCellEscaping() {
        // 2x2 表格：table 块持有 cells（行主序），cell 块 children 指向段落块
        List<JsonNode> blocks = List.of(
                block("{\"block_id\":\"t\",\"block_type\":31,\"table\":{\"property\":{\"row_size\":2,\"column_size\":2},"
                        + "\"cells\":[\"c1\",\"c2\",\"c3\",\"c4\"]}}"),
                block("{\"block_id\":\"c1\",\"block_type\":32,\"children\":[\"p1\"]}"),
                block("{\"block_id\":\"c2\",\"block_type\":32,\"children\":[\"p2\"]}"),
                block("{\"block_id\":\"c3\",\"block_type\":32,\"children\":[\"p3\"]}"),
                block("{\"block_id\":\"c4\",\"block_type\":32,\"children\":[\"p4\",\"p5\"]}"),
                textBlock("p1", 2, "text", textRun("表头一")),
                textBlock("p2", 2, "text", textRun("表头|二")),
                textBlock("p3", 2, "text", textRun("内容一")),
                textBlock("p4", 2, "text", textRun("段一")),
                textBlock("p5", 2, "text", textRun("段二")));

        String md = DocxMarkdownConverter.convert(blocks, null);

        assertContains(md, "| 表头一 | 表头\\|二 |");
        assertContains(md, "| --- | --- |");
        assertContains(md, "| 内容一 | 段一<br>段二 |");
        // cell/段落块不在主循环重复渲染
        assertEquals(md.indexOf("表头一"), md.lastIndexOf("表头一"));
    }

    @Test
    void unknownBlocksDegradeWithoutBreaking() {
        List<JsonNode> blocks = List.of(
                block("{\"block_id\":\"g\",\"block_type\":99,\"grid\":{\"column_size\":2}}"),
                block("{\"block_id\":\"img\",\"block_type\":27}"),
                textBlock("b1", 2, "text", textRun("正常段落")));

        String md = DocxMarkdownConverter.convert(blocks, "页面标题");

        assertTrue(md.startsWith("# 页面标题"), md);
        assertContains(md, "![图片]");
        assertContains(md, "正常段落");
    }

    @Test
    void mentionAndEquationDegrade() {
        List<JsonNode> blocks = List.of(
                textBlock("b1", 2, "text",
                        "[{\"mention_doc\":{\"title\":\"关联文档\"}},"
                                + "{\"equation\":{\"content\":\"x^2\"}}]"));

        String md = DocxMarkdownConverter.convert(blocks, null);

        assertContains(md, "关联文档");
        assertContains(md, "$x^2$");
    }
}
