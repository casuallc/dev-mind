package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-57 proc 帧 handler：收容校验（绝对路径/越界拒绝）、入参校验（非法 instanceId/
 * 未知 action/缺 argv）、status 无 pidfile 报 STOPPED。全程不起真实进程。
 */
class ProcHandlerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path workspace;

    private final LinkedBlockingQueue<Map<String, Object>> acks = new LinkedBlockingQueue<>();

    private ProcHandler handler() {
        ProcRegistry registry = new ProcRegistry(workspace.resolve("classify"));
        return new ProcHandler(workspace, registry, acks::add);
    }

    private JsonNode frame(String json) {
        return MAPPER.readTree(json);
    }

    private Map<String, Object> awaitAck() throws InterruptedException {
        Map<String, Object> ack = acks.poll(10, TimeUnit.SECONDS);
        assertNotNull(ack, "proc_ack 超时未回");
        return ack;
    }

    @Test
    void escapeWorkdirRejected() throws Exception {
        handler().handle(frame("""
                {"requestId":"r1","action":"start","instanceId":"i1","argv":["x"],
                 "workdir":"../escape","pidFile":"run/inst-i1/proc.pid","logFile":"logs/i1.log"}
                """));
        Map<String, Object> ack = awaitAck();
        assertEquals("proc_ack", ack.get("type"));
        assertEquals("r1", ack.get("requestId"));
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("越出收容根"), String.valueOf(ack));
    }

    @Test
    void absolutePidFileRejected() throws Exception {
        String abs = workspace.toAbsolutePath().toString().replace('\\', '/') + "/proc.pid";
        handler().handle(frame("""
                {"requestId":"r2","action":"start","instanceId":"i1","argv":["x"],
                 "workdir":"work","pidFile":"%s","logFile":"logs/i1.log"}
                """.formatted(abs)));
        Map<String, Object> ack = awaitAck();
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("绝对路径"), String.valueOf(ack));
    }

    @Test
    void unknownActionRejected() throws Exception {
        handler().handle(frame("""
                {"requestId":"r3","action":"wipe","instanceId":"i1","pidFile":"run/inst-i1/proc.pid"}
                """));
        Map<String, Object> ack = awaitAck();
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("未知 proc action"), String.valueOf(ack));
    }

    @Test
    void illegalInstanceIdRejected() throws Exception {
        handler().handle(frame("""
                {"requestId":"r4","action":"status","instanceId":"a/b","pidFile":"run/inst-a/proc.pid"}
                """));
        Map<String, Object> ack = awaitAck();
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("非法 instanceId"), String.valueOf(ack));
    }

    @Test
    void statusStoppedWithoutPidFile() throws Exception {
        handler().handle(frame("""
                {"requestId":"r5","action":"status","instanceId":"i9","pidFile":"run/inst-i9/proc.pid"}
                """));
        Map<String, Object> ack = awaitAck();
        assertEquals(Boolean.TRUE, ack.get("ok"));
        assertEquals("STOPPED", ack.get("status"));
    }

    @Test
    void startRequiresArgv() throws Exception {
        handler().handle(frame("""
                {"requestId":"r6","action":"start","instanceId":"i1",
                 "workdir":"work","pidFile":"run/inst-i1/proc.pid","logFile":"logs/i1.log"}
                """));
        Map<String, Object> ack = awaitAck();
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("argv"), String.valueOf(ack));
    }

    @Test
    void startFailsWhenWorkdirMissing() throws Exception {
        handler().handle(frame("""
                {"requestId":"r7","action":"start","instanceId":"i1","argv":["anything"],
                 "workdir":"packages/pkg-7-not-installed","pidFile":"run/inst-i1/proc.pid","logFile":"logs/i1.log"}
                """));
        Map<String, Object> ack = awaitAck();
        assertFalse((Boolean) ack.get("ok"));
        assertTrue(String.valueOf(ack.get("error")).contains("工作目录不存在"), String.valueOf(ack));
    }

    @Test
    void stopIsIdempotentWhenNotRunning() throws Exception {
        handler().handle(frame("""
                {"requestId":"r8","action":"stop","instanceId":"i1","pidFile":"run/inst-i1/proc.pid"}
                """));
        Map<String, Object> ack = awaitAck();
        assertEquals(Boolean.TRUE, ack.get("ok"));
        assertEquals("STOPPED", ack.get("status"));
    }
}
