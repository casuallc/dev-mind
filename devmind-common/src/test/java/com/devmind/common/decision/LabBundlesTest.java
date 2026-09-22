package com.devmind.common.decision;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 {@link LabBundles}：打包/读清单/解包三件事的契约——清单是唯一事实源、
 * 缺入口与畸形清单都按 IOException 收口（调用方据此判"拉包失败"），包内路径不得逃出目标目录。
 */
class LabBundlesTest {

    private static final byte[] PAYLOAD = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCRIPT = "print('hi')\n".getBytes(StandardCharsets.UTF_8);

    private static byte[] zipOf(String entryName, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] packSample(String payloadName) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("laya_eval.py", SCRIPT);
        return LabBundles.pack(new LabBundles.Manifest("laya_eval.py", payloadName), files, PAYLOAD);
    }

    @Test
    void packWritesManifestScriptAndPayload() throws Exception {
        byte[] zip = packSample("payload.json");
        LabBundles.Manifest m = LabBundles.readManifest(zip);
        assertEquals("laya_eval.py", m.entry());
        assertEquals("payload.json", m.payloadName());
        assertEquals(new String(SCRIPT, StandardCharsets.UTF_8),
                new String(LabBundles.readEntry(zip, "laya_eval.py"), StandardCharsets.UTF_8));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                new String(LabBundles.readEntry(zip, "payload.json"), StandardCharsets.UTF_8));

        // 清单必须真的在包里（runner 解包后按它找文件，不能靠约定）
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            boolean seen = false;
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                seen |= LabBundles.MANIFEST_NAME.equals(e.getName());
            }
            assertTrue(seen, "包里缺 " + LabBundles.MANIFEST_NAME);
        }
    }

    @Test
    void blankPayloadNameFallsBackToDefault() throws Exception {
        assertEquals(LabBundles.DEFAULT_PAYLOAD_NAME, LabBundles.readManifest(packSample(" ")).payloadName());
        assertEquals(LabBundles.DEFAULT_PAYLOAD_NAME, LabBundles.readManifest(packSample(null)).payloadName());
    }

    @Test
    void extractWritesPlainFiles() throws Exception {
        Path dir = Files.createTempDirectory("labbundles-test-");
        Map<String, Path> written = LabBundles.extract(packSample("payload.json"), dir);
        assertEquals(3, written.size(), String.valueOf(written.keySet()));
        assertTrue(Files.isRegularFile(dir.resolve("laya_eval.py")));
        assertTrue(Files.isRegularFile(dir.resolve("payload.json")));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                Files.readString(dir.resolve("payload.json"), StandardCharsets.UTF_8));
    }

    @Test
    void missingManifestIsIOException() {
        // 清单缺席必须报出来：否则 runner 会拿一个空 entry 去跑
        assertThrows(IOException.class, () -> LabBundles.readManifest(zipOf("laya_eval.py", SCRIPT)));
        assertThrows(IOException.class, () -> LabBundles.readManifest(new byte[0]));
    }

    @Test
    void malformedManifestIsIOException() {
        // Jackson 3 的解析异常是运行时异常；本层契约是"读不出来 = IOException"，不能漏出去
        assertThrows(IOException.class,
                () -> LabBundles.readManifest(zipOf(LabBundles.MANIFEST_NAME, "这不是 json".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void blankEntryIsIOException() {
        byte[] zip = zipOf(LabBundles.MANIFEST_NAME, "{\"payloadName\":\"payload.json\"}".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> LabBundles.readManifest(zip));
        assertTrue(e.getMessage().contains("entry"), e.getMessage());
    }

    @Test
    void readEntryMissingIsIOException() {
        IOException e = assertThrows(IOException.class, () -> LabBundles.readEntry(packSample("payload.json"), "nope.py"));
        assertTrue(e.getMessage().contains("nope.py"), e.getMessage());
    }

    @Test
    void extractNeutralizesZipSlip(@TempDir Path dir) throws Exception {
        // 包内写 ../evil.txt：取最后一段落进目标目录，绝不写到目录之外
        Path outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Path target = outside.resolve("inner");
        byte[] evil = zipOf("../evil.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        LabBundles.extract(evil, target);
        assertTrue(Files.isRegularFile(target.resolve("evil.txt")));
        assertFalse(Files.exists(outside.resolve("evil.txt")), "不得写出目标目录");
    }

    @Test
    void extractRejectsDotDotEntry() throws Exception {
        Path dir = Files.createTempDirectory("labbundles-slip-");
        IOException e = assertThrows(IOException.class, () -> LabBundles.extract(zipOf("..", new byte[0]), dir));
        assertTrue(e.getMessage().contains("非法文件名"), e.getMessage());
    }

    @Test
    void extractSkipsDirectoryEntries() throws Exception {
        // 目录条目（含中文名）不该被当成文件写下去：脚本与数据都是平铺的
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("脚本/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("laya_eval.py"));
            zip.write(SCRIPT);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Path dir = Files.createTempDirectory("labbundles-dir-");
        Map<String, Path> written = LabBundles.extract(out.toByteArray(), dir);
        assertEquals(1, written.size(), String.valueOf(written.keySet()));
        assertTrue(Files.isRegularFile(dir.resolve("laya_eval.py")));
    }
}
