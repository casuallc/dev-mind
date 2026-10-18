package com.devmind.common.agent.tool;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 工具输出/参数小助手（CAP-76 §6）：统一列表 limit 口径、时间格式与文本截断，
 * 避免各能力模块各写一份。时间格式与平台全局一致（{@code yyyy-MM-dd HH:mm:ss}）。
 */
public final class ToolText {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ToolText() {
    }

    /** 列表工具统一 limit：默认 20，上限 50。 */
    public static int limit(Map<String, Object> args) {
        Object v = args == null ? null : args.get("limit");
        int n = v instanceof Number num ? num.intValue() : 20;
        return Math.min(Math.max(n, 1), 50);
    }

    /** 可选正整数参数（如 tail），缺省返回 def。 */
    public static int intArg(Map<String, Object> args, String key, int def, int min, int max) {
        Object v = args == null ? null : args.get(key);
        int n = v instanceof Number num ? num.intValue() : def;
        return Math.min(Math.max(n, min), max);
    }

    /** 可选字符串参数（去空白；空串返回 null）。 */
    public static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    public static String time(Instant instant) {
        return instant == null ? "-" : TIME.format(instant);
    }

    public static String date(LocalDate date) {
        return date == null ? "-" : date.toString();
    }

    /** 按字符截断并补省略号；null 转空串。 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 多行文本压成单行（列表摘要用）。 */
    public static String oneLine(String text, int max) {
        return truncate(text == null ? null : text.replaceAll("\\s+", " "), max);
    }
}
