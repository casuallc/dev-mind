package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-73 VerifyHandler 打包/清单/diff 同步单测（静态方法直接驱动，不起 git/进程）。
 * 钉死：manifestOf 排序与哈希、zip↔unzip 往返、清单 JSON 往返、verifyHashes 篡改检出、
 * applyDiff 的 新增/覆盖/跳过/删除 四态与「manifest 外文件永不触碰」红线、路径越界防护。
 */
class VerifyHandlerBundleTest {

    @TempDir
    Path tmp;

    private static Path write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void manifest_sorted_and_hashable() throws Exception {
        Path tree = tmp.resolve("tree");
        write(tree, "libs/b.jar", "bbb");
        write(tree, "libs/a.jar", "aaa");
        write(tree, "conf/app.yml", "ccc");
        List<VerifyHandler.ManifestEntry> m = VerifyHandler.manifestOf(tree);
        assertEquals(3, m.size());
        // 按路径排序（稳定序列化）
        assertEquals(List.of("conf/app.yml", "libs/a.jar", "libs/b.jar"),
                m.stream().map(VerifyHandler.ManifestEntry::path).toList());
        assertEquals(64, m.get(0).sha256().length());
        assertEquals(3, m.get(0).size());
    }

    @Test
    void zip_unzip_manifest_round_trip() throws Exception {
        Path tree = tmp.resolve("tree");
        write(tree, "libs/a.jar", "aaa");
        write(tree, "web/index.html", "<html/>");
        List<VerifyHandler.ManifestEntry> m = VerifyHandler.manifestOf(tree);
        Path zip = tmp.resolve("bundle.zip");
        VerifyHandler.zipBundle(tree, m, zip);

        Path out = tmp.resolve("out");
        List<VerifyHandler.ManifestEntry> back = VerifyHandler.unzipBundle(zip, out);
        assertEquals(m, back);
        VerifyHandler.verifyHashes(out, back);
        assertEquals("<html/>", Files.readString(out.resolve("web/index.html"), StandardCharsets.UTF_8));

        // 清单 JSON 往返
        assertEquals(m, VerifyHandler.parseManifest(VerifyHandler.manifestJson(m)));
    }

    @Test
    void unzip_rejects_missing_manifest() throws Exception {
        Path tree = tmp.resolve("tree");
        write(tree, "a.txt", "x");
        Path zip = tmp.resolve("bundle.zip");
        // 手工打一个无清单的 zip
        try (var fos = Files.newOutputStream(zip); var zos = new java.util.zip.ZipOutputStream(fos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IllegalStateException.class, () -> VerifyHandler.unzipBundle(zip, tmp.resolve("out")));
    }

    @Test
    void unzip_rejects_zip_slip() throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (var fos = Files.newOutputStream(zip); var zos = new java.util.zip.ZipOutputStream(fos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(VerifyHandler.MANIFEST_ENTRY));
            zos.write("{\"files\":[]}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("../../evil.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IllegalStateException.class, () -> VerifyHandler.unzipBundle(zip, tmp.resolve("out")));
    }

    @Test
    void verify_hashes_detects_tampering() throws Exception {
        Path tree = tmp.resolve("tree");
        write(tree, "a.jar", "good");
        List<VerifyHandler.ManifestEntry> m = VerifyHandler.manifestOf(tree);
        Path zip = tmp.resolve("bundle.zip");
        VerifyHandler.zipBundle(tree, m, zip);
        Path out = tmp.resolve("out");
        List<VerifyHandler.ManifestEntry> back = VerifyHandler.unzipBundle(zip, out);
        Files.writeString(out.resolve("a.jar"), "tampered", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> VerifyHandler.verifyHashes(out, back));
    }

    @Test
    void apply_diff_add_update_skip_delete() throws Exception {
        Path tree = tmp.resolve("tree");   // 新包内容
        Path target = tmp.resolve("target"); // 环境现场
        write(tree, "libs/new.jar", "n1");
        write(tree, "libs/changed.jar", "v2");
        write(tree, "libs/same.jar", "s");
        write(target, "libs/changed.jar", "v1");
        write(target, "libs/same.jar", "s");
        write(target, "libs/old.jar", "o");
        write(target, "conf/local.yml", "keep"); // manifest 外文件——永不触碰

        List<VerifyHandler.ManifestEntry> newManifest = VerifyHandler.manifestOf(tree);
        // 旧 manifest：changed.jar(v1 哈希) / same.jar(同) / old.jar(新包无 → 删)
        Path oldTree = tmp.resolve("oldTree");
        write(oldTree, "libs/changed.jar", "v1");
        write(oldTree, "libs/same.jar", "s");
        write(oldTree, "libs/old.jar", "o");
        List<VerifyHandler.ManifestEntry> oldManifest = VerifyHandler.manifestOf(oldTree);

        int changed = VerifyHandler.applyDiff(tree, target, newManifest, oldManifest, null);
        assertEquals(3, changed); // 新增 new.jar + 覆盖 changed.jar + 删除 old.jar；same.jar 跳过
        assertEquals("n1", Files.readString(target.resolve("libs/new.jar"), StandardCharsets.UTF_8));
        assertEquals("v2", Files.readString(target.resolve("libs/changed.jar"), StandardCharsets.UTF_8));
        assertEquals("s", Files.readString(target.resolve("libs/same.jar"), StandardCharsets.UTF_8));
        assertTrue(Files.notExists(target.resolve("libs/old.jar")));
        // 红线：manifest 外文件不触碰
        assertEquals("keep", Files.readString(target.resolve("conf/local.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void apply_diff_first_run_full_sync_when_no_old_manifest() throws Exception {
        Path tree = tmp.resolve("tree");
        Path target = tmp.resolve("target");
        write(tree, "libs/a.jar", "a");
        write(tree, "libs/b.jar", "b");
        // 盘上已有同哈希文件 → 跳过；缺失的覆盖
        write(target, "libs/a.jar", "a");
        int changed = VerifyHandler.applyDiff(tree, target,
                VerifyHandler.manifestOf(tree), List.of(), null);
        assertEquals(1, changed); // 仅 b.jar
        assertEquals("b", Files.readString(target.resolve("libs/b.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void apply_diff_rejects_manifest_traversal() throws Exception {
        Path tree = tmp.resolve("tree");
        Path target = tmp.resolve("target");
        write(tree, "a.txt", "x");
        List<VerifyHandler.ManifestEntry> evil = List.of(
                new VerifyHandler.ManifestEntry("../escape.txt", "00", 1));
        assertThrows(IllegalStateException.class,
                () -> VerifyHandler.applyDiff(tree, target, evil, List.of(), null));
        // 绝对路径同样拒绝
        List<VerifyHandler.ManifestEntry> abs = List.of(
                new VerifyHandler.ManifestEntry("/etc/passwd", "00", 1));
        assertThrows(IllegalStateException.class,
                () -> VerifyHandler.applyDiff(tree, target, abs, List.of(), null));
    }

    @Test
    void manifest_json_escapes_quotes_and_backslash() throws Exception {
        List<VerifyHandler.ManifestEntry> m = List.of(
                new VerifyHandler.ManifestEntry("dir with space/q\"x.jar", "aa", 2));
        List<VerifyHandler.ManifestEntry> back = VerifyHandler.parseManifest(VerifyHandler.manifestJson(m));
        assertEquals(m, back);
    }
}
