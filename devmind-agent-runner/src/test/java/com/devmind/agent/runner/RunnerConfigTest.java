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

    // ---- 分发包模板防漂移：agent.properties.example 必须能被完整解析，且默认值与代码一致 ----

    @Test
    void distExampleTemplateParsesWithExpectedDefaults() throws Exception {
        Path example = Path.of(System.getProperty("user.dir"))
                .resolve("../devmind-dist/src/main/dist/config/agent.properties.example").normalize();
        assertTrue(Files.exists(example), "分发包模板缺失: " + example);
        RunnerConfig c = RunnerConfig.load(example);
        // 必填项（token 占位符也算已填，dev 脚本靠它识别未配置）
        assertTrue(c.token().startsWith("dmag_"), "token 占位符应为 dmag_ 开头: " + c.token());
        // 模板中显式给出的关键默认值与代码缺省一致
        org.junit.jupiter.api.Assertions.assertEquals("claude", c.executor());
        org.junit.jupiter.api.Assertions.assertEquals("acceptEdits", c.permissionMode());
        org.junit.jupiter.api.Assertions.assertEquals(4, c.maxConcurrent());
        org.junit.jupiter.api.Assertions.assertEquals("bash", c.execShell());
        assertTrue(c.execAllowlist().isEmpty(), "execAllowlist 默认空 = 拒绝一切 exec");
        assertTrue(c.terminalAllowlist() == null, "terminalAllowlist 缺省 = 内置只读档");
        org.junit.jupiter.api.Assertions.assertEquals(60, c.terminalTimeoutSec());
        org.junit.jupiter.api.Assertions.assertEquals(14, c.gcDays());
        org.junit.jupiter.api.Assertions.assertEquals(24, c.buildGcHours());
        org.junit.jupiter.api.Assertions.assertEquals(30, c.worktreeGcDays());
        assertTrue(c.gitProxyEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(18377, c.gitProxyPort());
        assertFalse(c.insecure());
        org.junit.jupiter.api.Assertions.assertEquals(10, c.toolchainRefreshMinutes());
    }

    private RunnerConfig load(String extra) throws Exception {
        Path f = dir.resolve("agent.properties");
        Files.writeString(f, "serverUrl=ws://127.0.0.1:8080/ws/agent\ntoken=dmag_t\n" + extra,
                StandardCharsets.UTF_8);
        return RunnerConfig.load(f);
    }
}
