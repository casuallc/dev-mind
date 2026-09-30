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
 * CAP-65 FileHandler 纯函数面：白名单精确匹配（归一化）/ 逃逸拒绝（.. / 绝对路径 / 符号链接父目录）/
 * 五类小操作语义（排序、二进制拒绝、超限拒绝、原子写、改名冲突、非空目录 recursive 门控）。
 * upload/download 的 HTTP 中转链路由 E2E 覆盖。
 */
class FileHandlerTest {

    // ---------------- 白名单匹配 ----------------

    @Test
    void matchRoot归一化后精确匹配(@TempDir Path base) {
        String root = base.toString();
        Path hit = FileHandler.matchRoot(List.of(root + "/"), root);
        assertEquals(Path.of(root), hit);
        // 反斜杠写法命中正斜杠白名单
        assertEquals(Path.of(root), FileHandler.matchRoot(List.of(root), root.replace('/', '\\')));
        // 目录前缀不等于 root（d:/data ≠ d:/data2 的兄弟前缀攻击）
        assertThrows(IllegalStateException.class,
                () -> FileHandler.matchRoot(List.of(root), root + "x"));
        // 子目录不是白名单根
        assertThrows(IllegalStateException.class,
                () -> FileHandler.matchRoot(List.of(root), root + "/sub"));
    }

    @Test
    void matchRoot空白名单一律拒绝(@TempDir Path base) {
        var e = assertThrows(IllegalStateException.class,
                () -> FileHandler.matchRoot(List.of(), base.toString()));
        assertTrue(e.getMessage().contains("白名单"), e.getMessage());
    }

    // ---------------- list / read ----------------

    @Test
    void list目录优先排序跳过点git(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve(".git"));
        Files.createDirectories(base.resolve("zdir"));
        Files.createDirectories(base.resolve("Adir"));
        Files.writeString(base.resolve("b.txt"), "hello");
        Map<String, Object> payload = FileHandler.opList(base, "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entries");
        assertEquals(3, entries.size(), entries.toString());
        assertEquals("Adir", entries.get(0).get("name"), "目录优先、名称忽略大小写排序");
        assertEquals("zdir", entries.get(1).get("name"));
        assertEquals("b.txt", entries.get(2).get("name"));
        assertEquals(true, entries.get(0).get("dir"));
        assertEquals(5L, entries.get(2).get("size"));
        assertTrue(entries.get(2).get("mtime") instanceof String, "mtime 以 ISO 字符串下发");
        assertFalse((Boolean) payload.get("truncated"));
        // 越界拒绝
        assertThrows(Exception.class, () -> FileHandler.opList(base, "../"));
    }

    @Test
    void read中文内容与大小(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("说明.txt"), "你好，世界");
        Map<String, Object> payload = FileHandler.opRead(base, "说明.txt");
        assertEquals("你好，世界", payload.get("content"));
        assertTrue((Long) payload.get("size") > 0);
    }

    @Test
    void read二进制与超限拒绝(@TempDir Path base) throws Exception {
        byte[] bin = {0x01, 0x00, 0x02};
        Files.write(base.resolve("a.bin"), bin);
        var e = assertThrows(IllegalStateException.class, () -> FileHandler.opRead(base, "a.bin"));
        assertTrue(e.getMessage().contains("二进制"), e.getMessage());

        byte[] big = new byte[513 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(base.resolve("big.txt"), big);
        var e2 = assertThrows(IllegalStateException.class, () -> FileHandler.opRead(base, "big.txt"));
        assertTrue(e2.getMessage().contains("512KB"), e2.getMessage());

        assertThrows(IllegalStateException.class, () -> FileHandler.opRead(base, ""), "read 缺 path");
    }

    // ---------------- write / rename / delete ----------------

    @Test
    void write原子落位且可覆盖(@TempDir Path base) throws Exception {
        // 父目录不存在拒绝
        assertThrows(IllegalStateException.class, () -> FileHandler.opWrite(base, "a/b.txt", "x"));
        Files.createDirectories(base.resolve("a"));
        Map<String, Object> payload = FileHandler.opWrite(base, "a/说明.txt", "中文内容");
        assertEquals("中文内容", Files.readString(base.resolve("a/说明.txt")));
        assertTrue((Long) payload.get("size") > 0);
        // 覆盖已存在文件
        FileHandler.opWrite(base, "a/说明.txt", "v2");
        assertEquals("v2", Files.readString(base.resolve("a/说明.txt")));
        // 不留临时残渣
        assertFalse(Files.exists(base.resolve("a/说明.txt.devmind-tmp")));
        // content 缺失 / 超限
        assertThrows(IllegalStateException.class, () -> FileHandler.opWrite(base, "a/x.txt", null));
        assertThrows(IllegalStateException.class,
                () -> FileHandler.opWrite(base, "a/x.txt", "x".repeat(513 * 1024)));
    }

    @Test
    void write符号链接父目录逃逸拒绝(@TempDir Path base, @TempDir Path outside) throws Exception {
        Path link = base.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "本机不支持创建符号链接（Windows 需开发者模式）: " + e);
            return;
        }
        // 写入路径经符号链接指向白名单外 → 拒绝
        assertThrows(IllegalStateException.class, () -> FileHandler.opWrite(base, "link/x.txt", "x"));
        // 读/删已有文件同样经 realPath 拦截
        Files.writeString(outside.resolve("secret.txt"), "s");
        assertThrows(Exception.class, () -> FileHandler.opRead(base, "link/secret.txt"));
        assertThrows(Exception.class, () -> FileHandler.opDelete(base, "link/secret.txt", false));
    }

    @Test
    void rename同目录改名与冲突拒绝(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("old.txt"), "x");
        Map<String, Object> payload = FileHandler.opRename(base, "old.txt", "新 名.txt");
        assertEquals("新 名.txt", payload.get("name"));
        assertFalse(Files.exists(base.resolve("old.txt")));
        assertTrue(Files.exists(base.resolve("新 名.txt")));
        // 目标已存在拒绝覆盖
        Files.writeString(base.resolve("b.txt"), "y");
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "b.txt", "新 名.txt"));
        // 非法 newName（分隔符/冒号/点名）拒绝
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "b.txt", "a/b"));
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "b.txt", "a:b"));
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "b.txt", ".."));
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "b.txt", ""));
        // 根目录本身不可改名
        assertThrows(IllegalStateException.class, () -> FileHandler.opRename(base, "", "x"));
    }

    @Test
    void delete文件空目录与recursive门控(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("f.txt"), "x");
        FileHandler.opDelete(base, "f.txt", false);
        assertFalse(Files.exists(base.resolve("f.txt")));

        Files.createDirectories(base.resolve("empty"));
        FileHandler.opDelete(base, "empty", false);
        assertFalse(Files.exists(base.resolve("empty")));

        // 非空目录：不带 recursive 拒绝，带 recursive 连子树删
        Files.createDirectories(base.resolve("tree/sub"));
        Files.writeString(base.resolve("tree/sub/深 层.txt"), "x");
        var e = assertThrows(IllegalStateException.class, () -> FileHandler.opDelete(base, "tree", false));
        assertTrue(e.getMessage().contains("recursive"), e.getMessage());
        assertTrue(Files.exists(base.resolve("tree/sub/深 层.txt")), "拒绝时不应误删");
        FileHandler.opDelete(base, "tree", true);
        assertFalse(Files.exists(base.resolve("tree")));

        // 根目录本身 / 不存在 / 越界 拒绝
        assertThrows(IllegalStateException.class, () -> FileHandler.opDelete(base, "", true));
        assertThrows(IllegalStateException.class, () -> FileHandler.opDelete(base, "ghost.txt", false));
        assertThrows(Exception.class, () -> FileHandler.opDelete(base, "../outside", true));
    }

    @Test
    void 深度上限32层(@TempDir Path base) throws Exception {
        String ok32 = "d/".repeat(31) + "f.txt"; // 32 段
        String tooDeep = "d/".repeat(32) + "f.txt"; // 33 段
        Files.createDirectories(base.resolve("d/".repeat(31)));
        Files.writeString(base.resolve(ok32), "x");
        assertEquals("x", FileHandler.opRead(base, ok32).get("content"));
        assertThrows(Exception.class, () -> FileHandler.opRead(base, tooDeep), "33 层超上限拒绝");
    }

    @Test
    void sha256Hex与JDK向量一致(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("a.txt"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileHandler.sha256Hex(base.resolve("a.txt")));
    }
}
