package com.devmind.knowledge.governance;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CAP-85 FR-29：64 位 SimHash（正文规范化后字符 3-gram 词频加权）。
 * 只用于近重复报告，不参与检索排序；相同/近似文档得到汉明距离很小的指纹。
 *
 * <p>规范化只保留字母/数字（含 CJK）并压空白，因此 Markdown 符号、大小写、换行差异
 * 不会把同一篇文档拆成两组。超长正文截前 {@value #MAX_CHARS} 字——SimHash 对长文尾部
 * 不敏感，而全量 3-gram 在 4MB 文档上会撑爆内存。</p>
 */
public final class SimHash {

    static final int MAX_CHARS = 120_000;

    private SimHash() {
    }

    /** 计算 16 位十六进制指纹；空白正文返回 null（调用方不落库，下次再算）。 */
    public static String of(String content) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return null;
        }
        Map<String, Integer> grams = grams(normalized);
        int[] vector = new int[64];
        for (Map.Entry<String, Integer> e : grams.entrySet()) {
            long hash = fnv1a(e.getKey());
            int weight = e.getValue();
            for (int bit = 0; bit < 64; bit++) {
                vector[bit] += ((hash >>> bit) & 1L) == 1L ? weight : -weight;
            }
        }
        long hash = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (vector[bit] >= 0) {
                hash |= (1L << bit);
            }
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }

    /** 汉明距离；非法指纹视为最大距离 64。 */
    public static int distance(String a, String b) {
        Long ha = parse(a);
        Long hb = parse(b);
        if (ha == null || hb == null) {
            return 64;
        }
        return Long.bitCount(ha ^ hb);
    }

    /** 解析 64 位指纹（16 位十六进制）；格式不符返回 null。 */
    public static Long parse(String hex) {
        if (hex == null || hex.length() != 16) {
            return null;
        }
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取第 band 段（0..3）的 16 位分桶键。 */
    public static int band(long hash, int band) {
        return (int) ((hash >>> (band * 16)) & 0xFFFFL);
    }

    static String normalize(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(lower.length(), MAX_CHARS));
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length() && sb.length() < MAX_CHARS; i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                pendingSpace = false;
                sb.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return sb.toString().strip();
    }

    private static Map<String, Integer> grams(String text) {
        Map<String, Integer> counts = new HashMap<>();
        if (text.length() <= 3) {
            counts.put(text, 1);
            return counts;
        }
        for (int i = 0; i + 3 <= text.length(); i++) {
            counts.merge(text.substring(i, i + 3), 1, Integer::sum);
        }
        return counts;
    }

    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
