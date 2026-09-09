package com.devmind.agent.runner;

import com.devmind.common.agent.exec.RunnerWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-36 execAllowlist 二次过滤：空白名单拒一切；逐行首 token 前缀命中；
 * shell 内置命令豁免；注释/空行跳过。
 */
class ExecHandlerAllowlistTest {

    @TempDir
    Path tmp;

    private ExecHandler handler(List<String> allowlist) {
        RunnerConfig cfg = new RunnerConfig("ws://localhost", "t", "", "acceptEdits",
                tmp, Map.of(), 2, "fake", tmp.resolve("ws"), 14, 360, 10, List.of(),
                allowlist, "bash", 24);
        return new ExecHandler(cfg, new RunnerWorkspace(tmp.resolve("ws")), f -> {
        });
    }

    @Test
    void emptyAllowlistRejectsEverything() {
        ExecHandler h = handler(List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> h.checkAllowlist("mvn -q compile"));
        assertTrue(e.getMessage().contains("execAllowlist"));
    }

    @Test
    void blankCommandRejected() {
        ExecHandler h = handler(List.of("mvn"));
        assertThrows(IllegalStateException.class, () -> h.checkAllowlist("  \n "));
    }

    @Test
    void allowedPrefixPasses() {
        ExecHandler h = handler(List.of("mvn", "./mvnw", "npm"));
        assertDoesNotThrow(() -> h.checkAllowlist("mvn -q compile"));
        assertDoesNotThrow(() -> h.checkAllowlist("./mvnw package -DskipTests"));
        assertDoesNotThrow(() -> h.checkAllowlist("npm run build"));
    }

    @Test
    void multilineScriptCheckedPerLine() {
        ExecHandler h = handler(List.of("mvn"));
        // 内置命令 echo/cd/export/if 豁免，mvn 命中
        assertDoesNotThrow(() -> h.checkAllowlist("""
                # 注释跳过
                cd sub
                export FOO=bar
                echo "开始构建"
                mvn -q compile
                if [ -f x ]; then echo ok; fi
                """));
        // 非白名单命令任一行即拒
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> h.checkAllowlist("mvn compile\ncurl http://evil\n"));
        assertTrue(e.getMessage().contains("curl"));
        assertThrows(IllegalStateException.class, () -> h.checkAllowlist("rm -rf /"));
    }

    @Test
    void prefixMatchCoversWrapperScripts() {
        ExecHandler h = handler(List.of("./mvnw"));
        assertDoesNotThrow(() -> h.checkAllowlist("./mvnw -v"));
        assertThrows(IllegalStateException.class, () -> h.checkAllowlist("mvn -v"));
    }
}
