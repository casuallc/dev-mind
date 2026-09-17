package com.devmind.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * CAP-44 FR-04 文本分块器：滑动窗口 + 语义边界回退（借鉴 WeKnora recursive splitter 简化版）。
 * 窗口满 size 字符后按分隔符优先级（段落 → 换行 → 句读 → 空格）向前找断点，
 * 找不到就硬切；相邻块重叠 overlap 字符保上下文连续。字符口径（中文按字），不做 tokenizer。
 */
public final class TextChunker {

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "！", "？", "；", "，", "、", " "};

    private TextChunker() {
    }

    /**
     * @param text 原文（null/空白 → 空列表）
     * @param size 块目标大小（字符）；实际块 ≤ size（硬切兜底）
     * @param overlap 相邻块重叠字符数（实际取 min(overlap, size/2)）
     */
    public static List<String> chunk(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = text.strip();
        if (t.length() <= size) {
            return List.of(t);
        }
        int overlapEff = Math.max(0, Math.min(overlap, size / 2));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(start + size, t.length());
            if (end < t.length()) {
                end = findBreak(t, start, end);
            }
            String piece = t.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= t.length()) {
                break;
            }
            int next = end - overlapEff;
            start = next > start ? next : end;
            while (start < end && Character.isWhitespace(t.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    /** 在 (start, end] 内按分隔符优先级找最靠后的断点；断点至少过半窗口，找不到返回 end（硬切） */
    private static int findBreak(String t, int start, int end) {
        int min = start + (end - start) / 2;
        for (String sep : SEPARATORS) {
            int idx = t.lastIndexOf(sep, end - 1);
            if (idx >= min) {
                return idx + sep.length();
            }
        }
        return end;
    }
}
