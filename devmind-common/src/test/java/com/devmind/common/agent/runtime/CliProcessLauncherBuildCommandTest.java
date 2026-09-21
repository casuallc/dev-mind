package com.devmind.common.agent.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CliProcessLauncher#buildCommand} 参数拼装回归：
 * resumeSessionId 非空 → 追加 {@code --resume <id>}（续接对话历史）；空 → 无该 flag（全新对话）；
 * CAP-50 → {@code --include-partial-messages} 默认带上、关开关时不带。
 */
class CliProcessLauncherBuildCommandTest {

    // 固定 claudePath 避免 where/which 探测（CI 无 claude 也能跑）
    private final CliProcessLauncher launcher =
            new CliProcessLauncher(RuntimeSettings.defaults().withClaudePath("claude"), new ObjectMapper());

    private List<String> build(String resumeSessionId) {
        return build(launcher, resumeSessionId);
    }

    private List<String> build(CliProcessLauncher l, String resumeSessionId) {
        return l.buildCommand(new SessionExecutor.LaunchContext(
                "s1", Path.of("."), "", "", "", Map.of(), resumeSessionId));
    }

    @Test
    void 无续接时不带resume旗标() {
        List<String> cmd = build(null);
        assertFalse(cmd.contains("--resume"));
        assertTrue(cmd.contains("--input-format"));
    }

    @Test
    void 空白续接id视同无续接() {
        assertFalse(build("").contains("--resume"));
        assertFalse(build("  ").contains("--resume"));
    }

    @Test
    void 有续接id时拼resume旗标() {
        List<String> cmd = build("cli-abc-123");
        int i = cmd.indexOf("--resume");
        assertTrue(i >= 0);
        assertEquals("cli-abc-123", cmd.get(i + 1));
    }

    // ---------------- CAP-50：partial messages ----------------

    @Test
    void 默认带partial旗标且前置条件齐备() {
        List<String> cmd = build(null);
        assertTrue(cmd.contains("--include-partial-messages"));
        // CLI 侧硬校验：--include-partial-messages 要求 -p + --output-format=stream-json，缺一即进程报错退出
        assertTrue(cmd.contains("-p"));
        int i = cmd.indexOf("--output-format");
        assertTrue(i >= 0);
        assertEquals("stream-json", cmd.get(i + 1));
    }

    @Test
    void 关掉开关时不带partial旗标() {
        CliProcessLauncher off = new CliProcessLauncher(
                RuntimeSettings.defaults().withClaudePath("claude").withIncludePartialMessages(false),
                new ObjectMapper());
        List<String> cmd = build(off, null);
        assertFalse(cmd.contains("--include-partial-messages"));
        // 关的只是增量，基础参数不受影响
        assertTrue(cmd.contains("--output-format"));
    }
}
