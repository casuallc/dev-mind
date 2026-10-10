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

    private RunnerConfig load(String extra) throws Exception {
        Path f = dir.resolve("agent.properties");
        Files.writeString(f, "serverUrl=ws://127.0.0.1:8080/ws/agent\ntoken=dmag_t\n" + extra,
                StandardCharsets.UTF_8);
        return RunnerConfig.load(f);
    }
}
