package com.devmind.agent.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RunnerConfig} 的 insecure 项解析（CAP-21 https 自签支持）：缺省 false、true 生效。 */
class RunnerConfigTest {

    @TempDir
    Path dir;

    @Test
    void insecureDefaultsFalse() throws Exception {
        assertFalse(load("").insecure());
    }

    @Test
    void insecureTrueParsed() throws Exception {
        assertTrue(load("insecure=true\n").insecure());
    }

    @Test
    void gitProxyDefaults() throws Exception {
        // CAP-58 FR-06：缺省开启、端口 18377
        RunnerConfig c = load("");
        assertTrue(c.gitProxyEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(18377, c.gitProxyPort());
    }

    @Test
    void gitProxyParsed() throws Exception {
        RunnerConfig c = load("gitProxyEnabled=false\ngitProxyPort=19377\n");
        assertFalse(c.gitProxyEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(19377, c.gitProxyPort());
    }

    // ---- CAP-106 FR-03/FR-05 工具链显式路径与周期重探测 ----

    @Test
    void toolchainDefaults() throws Exception {
        RunnerConfig c = load("");
        assertTrue(c.toolchainPaths().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(10, c.toolchainRefreshMinutes());
    }

    @Test
    void toolchainPathsParsedAndRefreshConfigurable() throws Exception {
        RunnerConfig c = load("toolchain.java=/home/jdk-21/bin/java\ntoolchain.git=/usr/bin\ntoolchainRefreshMinutes=0\n");
        org.junit.jupiter.api.Assertions.assertEquals("/home/jdk-21/bin/java", c.toolchainPaths().get("java"));
        org.junit.jupiter.api.Assertions.assertEquals("/usr/bin", c.toolchainPaths().get("git"));
        org.junit.jupiter.api.Assertions.assertEquals(0, c.toolchainRefreshMinutes(), "0 = 关闭周期重探测");
    }

    @Test
    void toolchainUnknownKeyFailsFast() throws Exception {
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> load("toolchain.python=/usr/bin/python3\n"));
        assertTrue(e.getMessage().contains("toolchain.python"), e.getMessage());
    }

    private RunnerConfig load(String extra) throws Exception {
        Path f = dir.resolve("agent.properties");
        Files.writeString(f, "serverUrl=ws://127.0.0.1:8080/ws/agent\ntoken=dmag_t\n" + extra,
                StandardCharsets.UTF_8);
        return RunnerConfig.load(f);
    }
}
