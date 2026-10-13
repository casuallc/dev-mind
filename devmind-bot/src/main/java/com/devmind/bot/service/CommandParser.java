package com.devmind.bot.service;

/**
 * 指令解析（CAP-75 FR-08）："/cmd 参数…" → Command；裸文本返回 null（走问答）。
 * 指令名大小写不敏感；"/" 后无内容或空白 = 非法指令（由调用方按 unknown 处理）。
 */
public final class CommandParser {

    public record Command(String name, String args) {
    }

    private CommandParser() {
    }

    /** 非指令返回 null；指令名转小写，args 去首尾空白（可为空串）。 */
    public static Command parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("/") || t.length() < 2) {
            return null;
        }
        int sp = t.indexOf(' ');
        int tab = t.indexOf('　'); // 中文全角空格兜底
        int cut = sp < 0 ? tab : (tab < 0 ? sp : Math.min(sp, tab));
        if (cut < 0) {
            return new Command(t.substring(1).toLowerCase(), "");
        }
        return new Command(t.substring(1, cut).toLowerCase(), t.substring(cut + 1).trim());
    }
}
