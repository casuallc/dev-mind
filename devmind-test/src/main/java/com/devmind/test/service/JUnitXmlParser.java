package com.devmind.test.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

/**
 * CAP-69 JUnit XML 解析：testsuite(s)/testcase → 用例级结果（pass/fail/skip + 耗时 + 失败文本）。
 *
 * <p>XML 来自 runner 节点上执行的脚本产物（半可信输入），解析器按不可信输入加固：
 * 禁 DOCTYPE / 外部实体 / XInclude（XXE 三件套），{@code <!DOCTYPE} 出现即解析失败。</p>
 *
 * <p>解析失败抛 {@link DevMindException}（BAD_REQUEST）——调用方（runScript）捕获后记
 * errorSummary 注记，不让一次坏 XML 把整次运行变成"没跑过"。</p>
 */
public final class JUnitXmlParser {

    /** 单条用例结果。status = pass / fail / skip（error 元素也归 fail，对齐 CAP-10 三态）；durationMs 由 time 秒换算 */
    public record ParsedCase(String name, String status, String error, long durationMs) {
    }

    private JUnitXmlParser() {
    }

    public static List<ParsedCase> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // XXE 加固：禁 DOCTYPE/外部实体/XInclude；属性不支持的解析器上静默跳过（JAXP 内置实现都支持）
            trySetFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(f, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
            trySetFeature(f, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "JUnit XML 解析失败: " + rootMessage(e));
        }
        Element root = doc.getDocumentElement();
        if (root == null || !(root.getTagName().equals("testsuite") || root.getTagName().equals("testsuites"))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "JUnit XML 解析失败: 根元素是 <" + (root == null ? "?" : root.getTagName()) + ">，期望 testsuite(s)");
        }
        List<ParsedCase> out = new ArrayList<>();
        NodeList cases = root.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Node n = cases.item(i);
            if (!(n instanceof Element tc)) {
                continue;
            }
            out.add(toCase(tc));
        }
        return out;
    }

    private static ParsedCase toCase(Element tc) {
        String name = attr(tc, "name");
        String classname = attr(tc, "classname");
        String full = (classname == null || classname.isBlank()) ? (name == null ? "(unnamed)" : name)
                : classname + "#" + (name == null ? "(unnamed)" : name);
        long durationMs = 0;
        String time = attr(tc, "time");
        if (time != null && !time.isBlank()) {
            try {
                durationMs = Math.round(Double.parseDouble(time.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // time 非法按 0，不挡解析
            }
        }
        Element failure = firstChild(tc, "failure");
        Element error = firstChild(tc, "error");
        Element skipped = firstChild(tc, "skipped");
        if (failure != null || error != null) {
            Element src = failure != null ? failure : error;
            String msg = attr(src, "message");
            String body = src.getTextContent();
            String err = (msg != null && !msg.isBlank() ? msg.strip() : "")
                    + (body != null && !body.isBlank() ? (msg != null && !msg.isBlank() ? "\n" : "") + body.strip() : "");
            return new ParsedCase(full, "fail", err.isBlank() ? "失败（无详情）" : err, durationMs);
        }
        if (skipped != null) {
            return new ParsedCase(full, "skip", null, durationMs);
        }
        return new ParsedCase(full, "pass", null, durationMs);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static void trySetFeature(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // 解析器实现不支持该特性：继续（JAXP 内置实现均支持）
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
