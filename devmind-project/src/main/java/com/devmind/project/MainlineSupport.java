package com.devmind.project;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主线实体共用的小工具：短 id 生成与分支 slug 化（CAP-13 各 Service 复用）。
 */
final class MainlineSupport {

    private MainlineSupport() {
    }

    static String shortId() {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(base.charAt(ThreadLocalRandom.current().nextInt(base.length())));
        }
        return sb.toString();
    }

    /** 标题/输入转分支 slug：小写字母数字与连字符，最长 48。 */
    static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String slug = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9一-鿿]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
