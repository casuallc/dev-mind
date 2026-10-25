package com.devmind.changelog.service;

import com.devmind.changelog.model.ChangelogEntry;
import com.devmind.changelog.model.ChangelogType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-87 CHANGELOG.md 解析器（Keep a Changelog 风格）。
 *
 * <p>只认二级标题段落：`## [1.0.0] - 2026-10-25`。段内三级标题映射变更类型，
 * `- 关联：CAP-87, WI-12` 行剥离为 refs，其余 Markdown 原样保留。Unreleased/未发布
 * 段落直接跳过——对应「未发布版本不对外可见」。</p>
 *
 * <p>坏段落不抛异常：单个版本解析失败只跳过该段，避免一份格式瑕疵让整个页面不可用。</p>
 */
@Component
public class ChangelogParser {

    private static final Pattern VERSION_HEADING = Pattern.compile(
            "^##\\s+\\[?([^\\]\\s]+)]?(?:\\s*[-–—(]\\s*(\\d{4}-\\d{2}-\\d{2}))?.*$");
    private static final Pattern TYPE_HEADING = Pattern.compile("^###\\s+(.+?)\\s*$");
    private static final Pattern REF_LINE = Pattern.compile(
            "^\\s{0,3}(?:[-*+]\\s+)?(?:关联(?:项)?|references?|refs?)\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REF_TOKEN = Pattern.compile(
            "(?i)\\b(?:CAP|REQ|WI)-\\d+\\b");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int SUMMARY_MAX = 160;

    public List<ChangelogEntry> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String text = markdown.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        List<ChangelogEntry> out = new ArrayList<>();
        String version = null;
        LocalDate releasedAt = null;
        List<String> body = new ArrayList<>();

        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ")) {
                flush(out, version, releasedAt, body);
                version = null;
                releasedAt = null;
                body = new ArrayList<>();
                Matcher m = VERSION_HEADING.matcher(line.trim());
                if (m.matches()) {
                    String raw = m.group(1).trim();
                    if (looksLikeVersion(raw)) {
                        version = normalizeVersion(raw);
                        releasedAt = parseDate(m.group(2));
                    }
                }
            } else if (version != null) {
                body.add(line);
            }
        }
        flush(out, version, releasedAt, body);
        out.sort((a, b) -> {
            int byVersion = compareVersions(b.version(), a.version());
            if (byVersion != 0) {
                return byVersion;
            }
            LocalDate da = a.releasedAt();
            LocalDate db = b.releasedAt();
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return db.compareTo(da);
        });
        return out;
    }

    private void flush(List<ChangelogEntry> out, String version, LocalDate releasedAt, List<String> body) {
        if (version == null) {
            return;
        }
        Set<ChangelogType> types = new LinkedHashSet<>();
        Set<String> refs = new LinkedHashSet<>();
        List<String> content = new ArrayList<>();
        for (String line : body) {
            Matcher ref = REF_LINE.matcher(line);
            if (ref.matches()) {
                Matcher token = REF_TOKEN.matcher(ref.group(1));
                while (token.find()) {
                    refs.add(token.group().toUpperCase(Locale.ROOT));
                }
                continue;
            }
            Matcher type = TYPE_HEADING.matcher(line);
            if (type.matches()) {
                ChangelogType mapped = mapType(type.group(1));
                if (mapped != null) {
                    types.add(mapped);
                }
            }
            content.add(line);
        }
        String contentMd = trimBlank(content);
        try {
            out.add(new ChangelogEntry(version, releasedAt, List.copyOf(types), List.copyOf(refs),
                    summarize(contentMd), contentMd));
        } catch (RuntimeException e) {
            // 单段异常不影响其余版本（防御未来解析逻辑扩展）
        }
    }

    private String trimBlank(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        return String.join("\n", lines.subList(start, end)).strip();
    }

    private ChangelogType mapType(String heading) {
        String h = heading.strip().toLowerCase(Locale.ROOT);
        if (h.contains("破坏") || h.contains("breaking")) {
            return ChangelogType.BREAKING;
        }
        if (h.contains("新增") || h.equals("added") || h.equals("add") || h.equals("features")) {
            return ChangelogType.ADDED;
        }
        if (h.contains("优化") || h.contains("改进") || h.equals("changed") || h.equals("improved")
                || h.equals("improvement") || h.equals("enhanced")) {
            return ChangelogType.IMPROVED;
        }
        if (h.contains("修复") || h.equals("fixed") || h.equals("fix") || h.equals("bug fixes")
                || h.equals("bugs")) {
            return ChangelogType.FIXED;
        }
        return null;
    }

    /** 二级标题只有版本号（或 v 前缀版本号）才作为条目；「更新日志」这类栏目标题跳过。 */
    private static boolean looksLikeVersion(String raw) {
        if (isUnreleased(raw)) {
            return false;
        }
        String v = raw.strip();
        if (v.isEmpty()) {
            return false;
        }
        char c = v.charAt(0);
        if (c == 'v' || c == 'V') {
            return v.length() > 1 && Character.isDigit(v.charAt(1));
        }
        return Character.isDigit(c);
    }

    private static boolean isUnreleased(String raw) {
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return v.equals("unreleased") || v.equals("un-released") || v.equals("未发布")
                || v.equals("待发布");
    }

    private static String normalizeVersion(String raw) {
        String v = raw.strip();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) {
            return v.substring(1);
        }
        return v;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.isEmpty() || line.startsWith("#") || line.startsWith("|")
                    || line.startsWith("---") || line.startsWith("***")) {
                continue;
            }
            line = LIST_MARKER.matcher(line).replaceFirst("");
            if (line.startsWith(">")) {
                line = line.substring(1).strip();
            }
            line = MD_IMAGE.matcher(line).replaceAll("");
            line = MD_LINK.matcher(line).replaceAll("$1");
            line = HTML_TAG.matcher(line).replaceAll("");
            line = line.replace("`", "").replace("**", "").replace("__", "")
                    .replace("*", "").replace("~~", "");
            if (!line.isBlank()) {
                sb.append(line).append(' ');
                if (sb.length() >= SUMMARY_MAX) {
                    break;
                }
            }
        }
        String summary = WHITESPACE.matcher(sb.toString()).replaceAll(" ").strip();
        if (summary.length() > SUMMARY_MAX) {
            summary = summary.substring(0, SUMMARY_MAX).strip() + "…";
        }
        return summary;
    }

    /** 版本号逐段数值比较；非数值段按字符串比较（数字段视为高于预发布文本段）。 */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-_+]");
        String[] pb = b.split("[.\\-_+]");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            boolean nx = x.matches("\\d+");
            boolean ny = y.matches("\\d+");
            int cmp;
            if (nx && ny) {
                cmp = compareNumeric(x, y);
            } else if (nx) {
                cmp = 1;
            } else if (ny) {
                cmp = -1;
            } else {
                cmp = x.compareToIgnoreCase(y);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.compareToIgnoreCase(b);
    }

    private static int compareNumeric(String x, String y) {
        try {
            return Long.compare(Long.parseLong(x), Long.parseLong(y));
        } catch (NumberFormatException e) {
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
    }
}
