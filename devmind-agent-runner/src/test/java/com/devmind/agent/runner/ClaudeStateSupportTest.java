package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClaudeStateSupport}：slug 归属键与 CAP-53 resume transcript 迁移。 */
class ClaudeStateSupportTest {

    @TempDir
    Path tmp;

    @Test
    void slugReplacesNonAlphanumericWithDash() {
        // Windows 形态：D:\apusic\dev-mind\x → D--apusic-dev-mind-x（分隔符与冒号都归一为 -）
        Path cwd = Path.of("D:", "apusic", "dev-mind", "x");
        assertEquals(cwd.toAbsolutePath().normalize().toString().replaceAll("[^a-zA-Z0-9]", "-"),
                ClaudeStateSupport.slug(cwd));
    }

    @Test
    void migrateCopiesOldSlugDirIntoNewWithoutOverwrite() throws Exception {
        Path configDir = tmp.resolve("claude-config");
        Path oldCwd = tmp.resolve("ws/proj/admin/worktrees/req-r1");
        Path newCwd = tmp.resolve("ws/proj/admin");
        Path oldDir = configDir.resolve("projects").resolve(ClaudeStateSupport.slug(oldCwd));
        // 旧归属：transcript + sidecar 目录 + memory
        Files.createDirectories(oldDir.resolve("cli-1/subagents"));
        Files.writeString(oldDir.resolve("cli-1.jsonl"), "{\"t\":1}\n", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("cli-1/subagents/a.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(oldDir.resolve("memory"));
        Files.writeString(oldDir.resolve("memory/MEMORY.md"), "# 记忆\n", StandardCharsets.UTF_8);
        // 新归属已有同名 memory（先到先得，不覆盖）
        Path newDir = configDir.resolve("projects").resolve(ClaudeStateSupport.slug(newCwd));
        Files.createDirectories(newDir.resolve("memory"));
        Files.writeString(newDir.resolve("memory/MEMORY.md"), "# 新记忆\n", StandardCharsets.UTF_8);

        ClaudeStateSupport.migrateTranscripts(configDir, oldCwd, newCwd, "cli-1");

        assertEquals("{\"t\":1}\n", Files.readString(newDir.resolve("cli-1.jsonl"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(newDir.resolve("cli-1/subagents/a.json")));
        assertEquals("# 新记忆\n", Files.readString(newDir.resolve("memory/MEMORY.md"),
                StandardCharsets.UTF_8), "既有文件不得被覆盖");
        // 旧目录保留（复制而非移动，由 claude 保留期自行回收）
        assertTrue(Files.isRegularFile(oldDir.resolve("cli-1.jsonl")));

        // 幂等：新归属已有该会话 jsonl → 不再复制
        Files.writeString(newDir.resolve("cli-1.jsonl"), "{\"t\":2}\n", StandardCharsets.UTF_8);
        ClaudeStateSupport.migrateTranscripts(configDir, oldCwd, newCwd, "cli-1");
        assertEquals("{\"t\":2}\n", Files.readString(newDir.resolve("cli-1.jsonl"), StandardCharsets.UTF_8));
    }

    @Test
    void migrateNoOpsWhenNothingToDo() throws Exception {
        Path configDir = tmp.resolve("claude-config");
        Path cwd = tmp.resolve("ws/proj/admin");
        // 空 resumeSessionId / 同 cwd / 旧目录不存在 → 全部 no-op 不建目录
        ClaudeStateSupport.migrateTranscripts(configDir, cwd, cwd, "cli-1");
        ClaudeStateSupport.migrateTranscripts(configDir, cwd.resolve("work"), cwd, "");
        ClaudeStateSupport.migrateTranscripts(configDir, cwd.resolve("work"), cwd, "cli-1");
        assertFalse(Files.exists(configDir.resolve("projects")));
    }
}
