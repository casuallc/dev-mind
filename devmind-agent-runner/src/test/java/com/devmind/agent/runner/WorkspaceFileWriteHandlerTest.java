package com.devmind.agent.runner;

import com.devmind.common.agent.runtime.CliEventParser;
import com.devmind.common.agent.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M2 runner 侧终端工作区文件直写核心（AgentRunnerMain.doWorkspaceFileWrite）：
 * 正常写入回 path+sha、目录自动创建；.git/ 拒写；越界（../、绝对路径）拒绝；
 * expectedSha 乐观锁（不符 → conflict + 当前 sha，不写盘；目标不存在 → conflict）；
 * base64 非法/缺字段/未知工作区键 → ok=false。
 */
class WorkspaceFileWriteHandlerTest {

    @TempDir
    Path base;

    private RunnerSessionRegistry registry() {
        RunnerSessionRegistry sessions = new RunnerSessionRegistry(
                new CliEventParser(JsonMapper.builder().build(), RuntimeSettings.defaults()),
                frame -> {
                });
        sessions.registerTerminalBinding("term-test0001", base);
        return sessions;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    void writeCreatesParentsAndReturnsSha() {
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "sub/dir/a.txt", b64("hello"), null);
        assertEquals(Boolean.TRUE, ack.get("ok"), String.valueOf(ack.get("error")));
        assertEquals("sub/dir/a.txt", ack.get("path"));
        try {
            assertEquals(sha256Hex("hello".getBytes(StandardCharsets.UTF_8)), ack.get("sha"));
            assertEquals("hello", Files.readString(base.resolve("sub/dir/a.txt")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void expectedShaMismatchConflictsWithoutWrite() throws Exception {
        Files.writeString(base.resolve("a.txt"), "old");
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "a.txt", b64("new"), "sha-not-match");
        assertEquals(Boolean.FALSE, ack.get("ok"));
        assertEquals(Boolean.TRUE, ack.get("conflict"));
        assertEquals(sha256Hex("old".getBytes(StandardCharsets.UTF_8)), ack.get("sha"),
                "冲突回应带当前 sha 供前端刷新续写");
        assertEquals("old", Files.readString(base.resolve("a.txt")), "冲突不写盘");
    }

    @Test
    void expectedShaMatchWrites() throws Exception {
        Files.writeString(base.resolve("a.txt"), "old");
        String sha = sha256Hex("old".getBytes(StandardCharsets.UTF_8));
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "a.txt", b64("new"), sha.toUpperCase());
        assertEquals(Boolean.TRUE, ack.get("ok"), "sha 比较应大小写不敏感: " + ack.get("error"));
        assertEquals("new", Files.readString(base.resolve("a.txt")));
    }

    @Test
    void expectedShaOnMissingFileConflicts() {
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "gone.txt", b64("x"), "anysha");
        assertEquals(Boolean.FALSE, ack.get("ok"));
        assertEquals(Boolean.TRUE, ack.get("conflict"));
        assertFalse(Files.exists(base.resolve("gone.txt")));
    }

    @Test
    void gitDirRejected() {
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", ".git/config", b64("x"), null);
        assertEquals(Boolean.FALSE, ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains(".git"));
    }

    @Test
    void escapeRejected() {
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "../outside.txt", b64("x"), null);
        assertEquals(Boolean.FALSE, ack.get("ok"));
        assertFalse(Files.exists(base.resolve("../x")));
        Map<String, Object> ack2 = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "/etc/passwd", b64("x"), null);
        assertEquals(Boolean.FALSE, ack2.get("ok"));
    }

    @Test
    void badBase64AndUnknownKeyRejected() {
        RunnerSessionRegistry sessions = registry();
        Map<String, Object> ack = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-test0001", "a.txt", "!!!not-base64!!!", null);
        assertEquals(Boolean.FALSE, ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("base64"));
        Map<String, Object> ack2 = AgentRunnerMain.doWorkspaceFileWrite(sessions,
                "term-unknown", "a.txt", b64("x"), null);
        assertEquals(Boolean.FALSE, ack2.get("ok"));
        assertTrue(String.valueOf(ack2.get("error")).contains("不在本节点"));
    }
}
