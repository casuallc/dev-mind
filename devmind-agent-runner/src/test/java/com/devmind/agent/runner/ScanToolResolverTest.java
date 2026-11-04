package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-66：runner 扫描工具路径解析——scan.&lt;engine&gt;.path 加载（未知引擎 fail-fast）、
 * 配置优先（文件→父目录 / 目录→自身）、配了但不存在按不可用（null）。
 */
class ScanToolResolverTest {

    @TempDir
    Path tmp;

    @Test
    void load解析引擎路径且跳过空值() throws Exception {
        Path props = tmp.resolve("agent.properties");
        Files.writeString(props, """
                serverUrl=http://x
                scan.sca.path=D:\\tools\\dependency-check\\bin\\dependency-check.bat
                scan.sast.path=
                """);
        Map<String, String> loaded = ScanToolResolver.load(props);
        assertEquals(1, loaded.size());
        assertTrue(loaded.get("sca").endsWith("dependency-check.bat"));
    }

    @Test
    void 未知引擎failFast防手滑静默() throws Exception {
        Path props = tmp.resolve("agent.properties");
        Files.writeString(props, "scan.sastt.path=/opt/semgrep\n");
        assertThrows(IllegalArgumentException.class, () -> ScanToolResolver.load(props));
    }

    @Test
    void 配置为文件取父目录为目录取自身() throws Exception {
        Path binDir = Files.createDirectories(tmp.resolve("dc").resolve("bin"));
        Path exe = Files.writeString(binDir.resolve("dependency-check"), "x");
        Map<String, String> conf = Map.of("sca", exe.toString());
        assertEquals(binDir.toAbsolutePath().normalize().toString(),
                ScanToolResolver.resolveBinDir(conf, "sca"));
        Map<String, String> confDir = Map.of("sca", binDir.toString());
        assertEquals(binDir.toAbsolutePath().normalize().toString(),
                ScanToolResolver.resolveBinDir(confDir, "sca"));
    }

    @Test
    void 配置路径不存在按不可用返回null() {
        Map<String, String> conf = Map.of("sca", tmp.resolve("no-such-dir").toString());
        assertNull(ScanToolResolver.resolveBinDir(conf, "sca"));
        assertNull(ScanToolResolver.resolveBinDir(conf, "bogus-engine"));
    }
}
