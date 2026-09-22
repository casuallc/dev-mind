package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-54 WorkspaceQueryHandler 安全与查询面：路径限定（绝对路径/.. 逃逸/深度上限拒绝）、
 * tree（目录优先排序、跳过 .git、相对路径键）、file（内容/大小、二进制拒绝、越界拒绝）。
 * diff 走真实 git 命令，纯函数面不覆盖（E2E 负责）。
 */
class WorkspaceQueryHandlerTest {

    @Test
    void resolveConfined拒绝逃逸(@TempDir Path base) {
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.resolveConfined(base, "../x", false));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.resolveConfined(base, "a/../../x", false));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.resolveConfined(base, "/etc/passwd", false));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.resolveConfined(base, "C:/Windows", false));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.resolveConfined(base, "a/b/c/d/e/f/g/h/i", false),
                "深度超 MAX_DEPTH=8 拒绝");
    }

    @Test
    void resolveConfined根与正常相对路径(@TempDir Path base) throws Exception {
        assertEquals(base.toAbsolutePath().normalize(),
                WorkspaceQueryHandler.resolveConfined(base, "", false));
        assertEquals(base.toAbsolutePath().normalize(),
                WorkspaceQueryHandler.resolveConfined(base, "/", false));
        Path p = WorkspaceQueryHandler.resolveConfined(base, "src/main/A.java", false);
        assertTrue(p.startsWith(base.toAbsolutePath().normalize()));
    }

    @Test
    void tree列一层目录优先且跳过点git(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve(".git"));
        Files.createDirectories(base.resolve("zdir"));
        Files.createDirectories(base.resolve("adir"));
        Files.writeString(base.resolve("b.txt"), "hello");
        Map<String, Object> payload = WorkspaceQueryHandler.tree(base, "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entries");
        assertEquals(3, entries.size(), entries.toString());
        assertEquals("adir", entries.get(0).get("name"), "目录优先按名称排序");
        assertEquals("zdir", entries.get(1).get("name"));
        assertEquals("b.txt", entries.get(2).get("name"));
        assertEquals(5L, entries.get(2).get("size"));
        assertFalse((Boolean) payload.get("truncated"));
        assertTrue(entries.stream().noneMatch(e -> ".git".equals(e.get("name"))));
        // 子目录下钻：path 键是相对工作区根的 POSIX 路径
        Map<String, Object> sub = WorkspaceQueryHandler.tree(base, "adir");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subEntries = (List<Map<String, Object>>) sub.get("entries");
        assertTrue(subEntries.isEmpty());
    }

    @Test
    void file读内容且拒绝二进制与越界(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("a.txt"), "你好 CAP-54");
        Map<String, Object> payload = WorkspaceQueryHandler.file(base, "a.txt");
        assertEquals("你好 CAP-54", payload.get("content"));

        Files.write(base.resolve("b.bin"), new byte[]{0, 1, 2});
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.file(base, "b.bin"), "NUL 嗅探拒绝二进制");
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.file(base, "../outside.txt"));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.file(base, ""));
        assertThrows(Exception.class, () -> WorkspaceQueryHandler.file(base, "missing.txt"));
    }

    @Test
    void file超大拒绝(@TempDir Path base) throws Exception {
        byte[] big = new byte[WorkspaceQueryHandler.FILE_CAP_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(base.resolve("big.txt"), big);
        Exception e = assertThrows(Exception.class, () -> WorkspaceQueryHandler.file(base, "big.txt"));
        assertTrue(e.getMessage().contains("过大"), e.getMessage());
    }
}
