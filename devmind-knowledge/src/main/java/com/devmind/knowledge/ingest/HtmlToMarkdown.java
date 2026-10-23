package com.devmind.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * CAP-84 FR-03：Tika XHTML → Markdown。保留标题层级、列表、表格、代码块与行内强调，
 * 让 PDF/Word/PPT/HTML 解析结果与原生 Markdown 一样可被 {@link TextChunker} 结构化切分。
 */
final class HtmlToMarkdown {

    private HtmlToMarkdown() {
    }

    static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, meta, link").remove();
        StringBuilder sb = new StringBuilder(html.length() / 2);
        appendChildren(doc.body(), sb);
        return sb.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void appendChildren(Element parent, StringBuilder sb) {
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode text) {
                String value = text.text().replace('\u00a0', ' ').strip();
                if (!value.isEmpty()) {
                    sb.append(collapse(value)).append("\n\n");
                }
            } else if (node instanceof Element el) {
                appendBlock(el, sb, 0);
            }
        }
    }

    private static void appendBlock(Element el, StringBuilder sb, int depth) {
        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blankLine(sb);
                int level = el.tagName().charAt(1) - '0';
                sb.append("#".repeat(level)).append(' ').append(inline(el).strip()).append("\n\n");
            }
            case "p" -> {
                String text = inline(el).strip();
                if (!text.isEmpty()) {
                    blankLine(sb);
                    sb.append(text).append("\n\n");
                }
            }
            case "ul", "ol" -> appendList(el, sb, depth);
            case "table" -> appendTable(el, sb);
            case "pre" -> appendPre(el, sb);
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                appendChildren(el, inner);
                String text = inner.toString().strip();
                if (!text.isEmpty()) {
                    blankLine(sb);
                    for (String line : text.split("\n", -1)) {
                        sb.append("> ").append(line).append('\n');
                    }
                    sb.append('\n');
                }
            }
            case "hr" -> {
                blankLine(sb);
                sb.append("---\n\n");
            }
            case "br" -> sb.append('\n');
            case "div", "section", "article", "main", "header", "footer", "aside" ->
                    appendChildren(el, sb);
            default -> {
                if (hasBlockChildren(el)) {
                    appendChildren(el, sb);
                } else {
                    String text = inline(el).strip();
                    if (!text.isEmpty()) {
                        blankLine(sb);
                        sb.append(text).append("\n\n");
                    }
                }
            }
        }
    }

    private static void appendList(Element list, StringBuilder sb, int depth) {
        blankLine(sb);
        boolean ordered = "ol".equals(list.tagName());
        int index = 1;
        String indent = "  ".repeat(Math.max(0, depth));
        for (Element li : list.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            StringBuilder content = new StringBuilder();
            for (Node child : li.childNodes()) {
                if (child instanceof Element e && ("ul".equals(e.tagName()) || "ol".equals(e.tagName()))) {
                    continue;
                }
                content.append(inline(child));
            }
            String text = collapse(content.toString().strip());
            String prefix = ordered ? (index++) + ". " : "- ";
            sb.append(indent).append(prefix).append(text).append('\n');
            for (Element nested : li.children()) {
                if ("ul".equals(nested.tagName()) || "ol".equals(nested.tagName())) {
                    appendList(nested, sb, depth + 1);
                }
            }
        }
        sb.append('\n');
    }

    private static void appendTable(Element table, StringBuilder sb) {
        List<Element> rows = new ArrayList<>();
        for (Element section : table.children()) {
            if ("tr".equals(section.tagName())) {
                rows.add(section);
            } else {
                for (Element tr : section.children()) {
                    if ("tr".equals(tr.tagName())) {
                        rows.add(tr);
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        List<List<String>> cells = new ArrayList<>(rows.size());
        int columns = 0;
        for (Element row : rows) {
            List<String> values = new ArrayList<>();
            for (Element cell : row.children()) {
                if ("td".equals(cell.tagName()) || "th".equals(cell.tagName())) {
                    values.add(collapse(inline(cell).strip()).replace("|", "\\|"));
                }
            }
            columns = Math.max(columns, values.size());
            cells.add(values);
        }
        if (columns == 0) {
            return;
        }
        blankLine(sb);
        for (int i = 0; i < cells.size(); i++) {
            List<String> row = cells.get(i);
            sb.append('|');
            for (int c = 0; c < columns; c++) {
                sb.append(' ').append(c < row.size() ? row.get(c) : "").append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|');
                for (int c = 0; c < columns; c++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');
    }

    private static void appendPre(Element pre, StringBuilder sb) {
        String code = pre.wholeText().replace("\r\n", "\n").replace('\r', '\n');
        code = code.stripTrailing();
        if (code.isBlank()) {
            return;
        }
        String language = "";
        for (String cls : pre.classNames()) {
            if (cls.startsWith("language-")) {
                language = cls.substring("language-".length());
            } else if (cls.startsWith("lang-")) {
                language = cls.substring("lang-".length());
            }
        }
        blankLine(sb);
        sb.append("```").append(language).append('\n').append(code).append("\n```\n\n");
    }

    private static String inline(Node node) {
        if (node instanceof TextNode text) {
            return collapse(text.text().replace('\u00a0', ' '));
        }
        if (node instanceof Element el) {
            String inner = inlineChildren(el);
            return switch (el.tagName()) {
                case "br" -> "\n";
                case "strong", "b" -> inner.isBlank() ? "" : "**" + inner.strip() + "**";
                case "em", "i" -> inner.isBlank() ? "" : "*" + inner.strip() + "*";
                case "del", "s", "strike" -> inner.isBlank() ? "" : "~~" + inner.strip() + "~~";
                case "code" -> inner.isBlank() ? "" : codeSpan(inner);
                case "a" -> link(el, inner);
                case "img" -> image(el);
                default -> inner;
            };
        }
        return "";
    }

    private static String inlineChildren(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node child : el.childNodes()) {
            sb.append(inline(child));
        }
        return sb.toString();
    }

    private static String codeSpan(String text) {
        String trimmed = text.strip();
        String fence = trimmed.contains("`") ? "``" : "`";
        return fence + trimmed + fence;
    }

    private static String link(Element a, String inner) {
        String href = a.attr("href").strip();
        String text = inner.isBlank() ? href : inner.strip();
        if (href.isBlank() || href.startsWith("javascript:")) {
            return text;
        }
        return "[" + text + "](" + href + ")";
    }

    private static String image(Element img) {
        String src = img.attr("src").strip();
        if (src.isBlank()) {
            return "";
        }
        String alt = img.attr("alt").strip();
        return "![" + alt + "](" + src + ")";
    }

    private static boolean hasBlockChildren(Element el) {
        for (Element child : el.children()) {
            switch (child.tagName()) {
                case "p", "div", "section", "article", "ul", "ol", "table", "pre", "blockquote",
                     "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    return true;
                }
                default -> {
                    // inline wrapper: keep looking
                }
            }
        }
        return false;
    }

    private static void blankLine(StringBuilder sb) {
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == '\n' || sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\t')) {
            len--;
        }
        sb.setLength(len);
        if (len > 0) {
            sb.append("\n\n");
        }
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
