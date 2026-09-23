package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-57 受管进程对账：runner「重启」（新注册表实例扫同一 classify/run 根）后，
 * pid 活着的实例登记回来，死掉的清尸（proc.json 删除、不登记）。
 */
class ProcRegistryReconcileTest {

    @TempDir
    Path classifyRoot;

    @Test
    void reconcileRestoresLiveProcess() {
        long selfPid = ProcessHandle.current().pid();
        ProcRegistry first = new ProcRegistry(classifyRoot);
        first.register("edge-a", selfPid, "python sidecar.py");

        // 模拟 runner 重启：全新注册表扫同一 run 根
        ProcRegistry restarted = new ProcRegistry(classifyRoot);
        assertEquals(1, restarted.reconcile());
        var entry = restarted.entryOf("edge-a");
        assertTrue(entry.isPresent());
        assertEquals(selfPid, entry.get().pid());
        assertEquals("python sidecar.py", entry.get().command());
    }

    @Test
    void reconcileSweepsDeadProcess() throws Exception {
        // 起一个立刻退出的进程拿一个确定死掉的 pid（java -version 跨平台可用）
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process p = new ProcessBuilder(javaBin, "-version").start();
        long deadPid = p.pid();
        p.waitFor();

        ProcRegistry first = new ProcRegistry(classifyRoot);
        first.register("edge-dead", deadPid, "x");

        ProcRegistry restarted = new ProcRegistry(classifyRoot);
        assertEquals(0, restarted.reconcile());
        assertTrue(restarted.entryOf("edge-dead").isEmpty());
        assertFalse(Files.exists(classifyRoot.resolve("run/inst-edge-dead/proc.json")), "死尸记录应被清理");
    }

    @Test
    void registerWritesAndUnregisterDeletes() throws Exception {
        ProcRegistry registry = new ProcRegistry(classifyRoot);
        registry.register("i1", 12345, "cmd");
        Path procFile = classifyRoot.resolve("run/inst-i1/proc.json");
        assertTrue(Files.isRegularFile(procFile));
        assertTrue(Files.readString(procFile).contains("\"pid\":12345"));

        registry.unregister("i1");
        assertFalse(Files.exists(procFile));
        assertTrue(registry.entryOf("i1").isEmpty());
    }

    @Test
    void malformedRecordSkippedAndSwept() throws Exception {
        Path dir = classifyRoot.resolve("run/inst-bad");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("proc.json"), "not-json{{{");

        ProcRegistry registry = new ProcRegistry(classifyRoot);
        assertEquals(0, registry.reconcile()); // 不抛、不登记
        assertTrue(registry.entryOf("bad").isEmpty());
    }

    @Test
    void emptyRunRootReconcilesToZero() {
        assertEquals(0, new ProcRegistry(classifyRoot).reconcile());
    }
}
