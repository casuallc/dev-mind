package com.devmind.worklog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CAP-74 M3：条目 day 文件（{@code <subdir>/entries/yyyy-MM-dd.md}）的解析与序列化，纯函数无依赖。
 *
 * <p>格式约定：
 * <pre>
 * # 2026-10-12 工作条目            ← 首个条目注释之前的一切 = preamble，逐字节保留
 *
 * &lt;!-- devmind:entry id=&lt;32hex&gt; type=DEV hours=1.5 source=MANUAL req=- jira=- created=2026-10-12T02:30:00Z --&gt;
 * ## 标题
 * 正文（到下一条目注释或文件尾）
 * </pre>
 *
 * <ul>
 *   <li>元数据属性固定顺序、空格分隔、值不含空格，空值用 {@code -}；未知 k=v 存 extraAttrs 原序写回（平台外编辑不丢）。</li>
 *   <li>正文内部空行/子标题/代码块保留；正文尾部空行在重写时归一化为条目间恰好一个空行（分隔符不属于正文）。</li>
 *   <li>容错：缺 id 解析成立（调用方重写时惰性补发）；hours 缺席/非法按 0；单行坏不丢整文件。</li>
 * </ul>
 */
public final class WorklogEntryFile {

    public static final String MARKER_PREFIX = "<!-- devmind:entry ";
    public static final String MARKER_SUFFIX = " -->";

    private WorklogEntryFile() {}

    /** 单条条目。body 不含尾部空行；extraAttrs 保序往返未知属性（迁移用 legacy=<db id> 走这里）。 */
    public record ParsedEntry(String id, String entryType, int minutes, String source,
                              Long repoId, String commitSha, String requirementId, String jiraIssueKey,
                              Instant createdAt, String title, String body,
                              Map<String, String> extraAttrs) {
        public ParsedEntry {
            extraAttrs = extraAttrs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraAttrs);
        }
    }

    /** 一日文件：preamble 逐字节保留 + 有序条目列表。 */
    public record DayFile(String preamble, List<ParsedEntry> entries) {
        public DayFile {
            entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
    }

    /** 新建空文件的初始 preamble。 */
    public static String initialPreamble(String dateIso) {
        return "# " + dateIso + " 工作条目\n\n";
    }

    /** 补发惰性 id：缺 id 的条目现场发 uuid（重写时落回文件）。 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** hours（0.25 步进）→ 分钟，15 分钟粒度四舍五入兜底。 */
    public static int toMinutes(double hours) {
        return (int) Math.round(hours * 60 / 15.0) * 15;
    }

    public static DayFile parse(String content) {
        if (content == null || content.isBlank()) {
            return new DayFile(content == null ? "" : content, List.of());
        }
        // 统一 \r\n → \n 解析（仓内文件平台写入恒 \n；平台外编辑可能带 \r\n，重写时归一）
        String text = content.replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        StringBuilder preamble = new StringBuilder();
        List<ParsedEntry> entries = new ArrayList<>();
        int i = 0;
        for (; i < lines.length; i++) {
            if (isMarker(lines[i])) {
                break;
            }
            preamble.append(lines[i]).append('\n');
        }
        // split(-1) 在文本以 \n 结尾时会多出一个空串尾元素；preamble 重构时逐行补 \n 已还原，去掉多余尾
        String preambleText = preamble.toString();
        if (i == lines.length && preambleText.length() > text.length()) {
            preambleText = preambleText.substring(0, text.length());
        }
        while (i < lines.length) {
            String marker = lines[i];
            if (!isMarker(marker)) {
                // 容错：条目区里的非注释散行归入上一条目正文不可能（散行在正文循环里吃掉），这里只会是解析起点异常，跳过
                i++;
                continue;
            }
            Map<String, String> attrs = parseAttrs(marker);
            i++;
            // 标题：marker 后第一个 ## 行；之前的空行跳过
            while (i < lines.length && lines[i].isBlank()) {
                i++;
            }
            String title = "";
            if (i < lines.length && lines[i].startsWith("## ") && !isMarker(lines[i])) {
                title = lines[i].substring(3);
                i++;
            }
            // 正文：到下一条 marker 或文件尾；去掉首行空行与尾部空行（尾部空行是分隔符）
            StringBuilder body = new StringBuilder();
            if (i < lines.length && !isMarker(lines[i]) && lines[i].isBlank()) {
                i++; // 标题与正文间的单个空行
            }
            while (i < lines.length && !isMarker(lines[i])) {
                body.append(lines[i]).append('\n');
                i++;
            }
            entries.add(toEntry(attrs, title, stripTrailingNewlines(body.toString())));
        }
        return new DayFile(preambleText, entries);
    }

    public static String render(DayFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append(file.preamble() == null ? "" : file.preamble());
        for (ParsedEntry e : file.entries()) {
            sb.append(renderMarker(e)).append('\n');
            sb.append("## ").append(e.title() == null ? "" : e.title()).append('\n');
            if (e.body() != null && !e.body().isEmpty()) {
                sb.append(e.body()).append('\n');
            }
            sb.append('\n'); // 条目间分隔空行
        }
        return sb.toString();
    }

    private static String renderMarker(ParsedEntry e) {
        StringBuilder sb = new StringBuilder(MARKER_PREFIX);
        sb.append("id=").append(e.id() == null || e.id().isBlank() ? newId() : e.id());
        sb.append(" type=").append(e.entryType() == null ? "DEV" : e.entryType());
        sb.append(" hours=").append(toHours(e.minutes()));
        sb.append(" source=").append(e.source() == null ? "MANUAL" : e.source());
        sb.append(" repo=").append(e.repoId() == null ? "-" : e.repoId());
        sb.append(" sha=").append(dash(e.commitSha()));
        sb.append(" req=").append(dash(e.requirementId()));
        sb.append(" jira=").append(dash(e.jiraIssueKey()));
        sb.append(" created=").append(e.createdAt() == null ? "-" : e.createdAt().toString());
        for (Map.Entry<String, String> x : e.extraAttrs().entrySet()) {
            sb.append(' ').append(x.getKey()).append('=').append(x.getValue());
        }
        return sb.append(MARKER_SUFFIX).toString();
    }

    private static ParsedEntry toEntry(Map<String, String> attrs, String title, String body) {
        Map<String, String> extra = new LinkedHashMap<>(attrs);
        String id = extra.remove("id");
        String type = extra.remove("type");
        String hours = extra.remove("hours");
        String source = extra.remove("source");
        String repo = extra.remove("repo");
        String sha = extra.remove("sha");
        String req = extra.remove("req");
        String jira = extra.remove("jira");
        String created = extra.remove("created");
        return new ParsedEntry(
                blank(id) ? null : id,
                blank(type) ? "DEV" : type,
                parseMinutes(hours),
                blank(source) ? "MANUAL" : source,
                parseLong(repo),
                undash(sha), undash(req), undash(jira),
                parseInstant(created),
                title, body, extra);
    }

    private static boolean isMarker(String line) {
        String s = line.strip();
        return s.startsWith(MARKER_PREFIX) && s.endsWith(MARKER_SUFFIX);
    }

    private static Map<String, String> parseAttrs(String markerLine) {
        String s = markerLine.strip();
        String inner = s.substring(MARKER_PREFIX.length(), s.length() - MARKER_SUFFIX.length());
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String kv : inner.split(" +")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                attrs.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return attrs;
    }

    private static int parseMinutes(String hours) {
        if (blank(hours) || "-".equals(hours)) {
            return 0;
        }
        try {
            return toMinutes(Double.parseDouble(hours));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 分钟 → hours 字符串：60→"1"、90→"1.5"、15→"0.25"。 */
    static String toHours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60))
                .stripTrailingZeros().toPlainString();
    }

    private static Long parseLong(String v) {
        if (blank(v) || "-".equals(v)) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String v) {
        if (blank(v) || "-".equals(v)) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String dash(String v) {
        return blank(v) ? "-" : v;
    }

    private static String undash(String v) {
        return blank(v) || "-".equals(v) ? null : v;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
