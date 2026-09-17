package com.devmind.knowledge.chunk;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextChunker：短文本单块、长文本尺寸上界、语义边界回退、重叠连续、确定性。
 */
class TextChunkerTest {

    @Test
    void shortTextIsSingleChunk() {
        assertEquals(List.of(), TextChunker.chunk(null, 800, 100));
        assertEquals(List.of(), TextChunker.chunk("   ", 800, 100));
        assertEquals(List.of("你好，世界"), TextChunker.chunk("你好，世界", 800, 100));
    }

    @Test
    void longTextSplitsWithinSize() {
        String text = "段落。".repeat(500); // 1500 字符
        List<String> chunks = TextChunker.chunk(text, 800, 100);
        assertTrue(chunks.size() >= 2, "长文本应切多块");
        for (String c : chunks) {
            assertTrue(c.length() <= 800, "每块不超 size，实际 " + c.length());
        }
        assertTrue(chunks.get(0).startsWith("段落。"), "首块含开头");
        assertTrue(chunks.get(chunks.size() - 1).endsWith("段落。"), "末块含结尾");
        assertEquals(chunks, TextChunker.chunk(text, 800, 100), "同输入结果确定");
    }

    @Test
    void prefersParagraphBreak() {
        String a = "甲".repeat(60);
        String b = "乙".repeat(60);
        List<String> chunks = TextChunker.chunk(a + "\n\n" + b, 80, 10);
        assertEquals(a, chunks.get(0), "应优先在段落边界断开");
    }

    @Test
    void overlapKeepsContext() {
        // 无分隔符纯硬切：相邻块应有 overlap 字符重叠
        String text = "abcdefghijklmnopqrstuvwxyz".repeat(10); // 260 字符无分隔符
        List<String> chunks = TextChunker.chunk(text, 100, 20);
        assertTrue(chunks.size() >= 2);
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String curr = chunks.get(i);
            assertEquals(prev.substring(prev.length() - 20), curr.substring(0, 20),
                    "硬切相邻块应重叠 20 字符");
        }
    }
}
