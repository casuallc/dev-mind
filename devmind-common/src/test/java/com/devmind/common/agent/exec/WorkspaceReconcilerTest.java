package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceReconciler} 重启对账断言：存活 pid（启动时刻吻合）→ 整树回收计入 reaped；
 * 死 pid / 无 pid 文件 → 只登记 ownerlessDirs 不删；非法目录名跳过。
 */
class WorkspaceReconcilerTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path root;

    private Path sessionDir(String projectId, String sid) throws Exception {
        Path dir = root.resolve(projectId).resolve("sessions").resolve(sid);
        Files.createDirectories(dir);
        return dir;
    }

    private static Process spawnSleeper() throws Exception {
        ProcessBuilder pb = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "ping -n 300 127.0.0.1 >nul")
                : new ProcessBuilder("sh", "-c", "sleep 300");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    @Test
    void liveOrphanIsReaped() throws Exception {
        Process orphan = spawnSleeper();
        Path dir = sessionDir("p1", "s-live");
        WorkspaceReconciler.writePidFile(dir, orphan);
        assertTrue(Files.exists(dir.resolve(WorkspaceReconciler.PID_FILE)));

        var report = new WorkspaceReconciler(root).reconcile();

        assertEquals(1, report.reaped().size());
        assertEquals("s-live", report.reaped().get(0).sessionId());
        assertTrue(report.ownerlessDirs().isEmpty());
        orphan.waitFor(10, TimeUnit.SECONDS);
        assertFalse(orphan.isAlive());
    }

    @Test
    void deadPidAndMissingPidFileAreOwnerlessOnly() throws Exception {
        // 死 pid：起一个短命进程拿 pid 写文件
        Process dead = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "echo x").start()
                : new ProcessBuilder("sh", "-c", "echo x").start();
        dead.waitFor(10, TimeUnit.SECONDS);
        Path dirWithDeadPid = sessionDir("p1", "s-dead");
        WorkspaceReconciler.writePidFile(dirWithDeadPid, dead);

        // 无 pid 文件
        Path dirNoPid = sessionDir("p2", "s-nopid");
        // chat 沙箱
        Path chatDir = root.resolve("_chat").resolve("c1");
        Files.createDirectories(chatDir);
        // 非法目录名（含空格）跳过
        Files.createDirectories(root.resolve("p3").resolve("sessions").resolve("bad name"));

        var report = new WorkspaceReconciler(root).reconcile();

        assertTrue(report.reaped().isEmpty());
        assertEquals(3, report.ownerlessDirs().size());
        // 登记不删除
        assertTrue(Files.isDirectory(dirWithDeadPid));
        assertTrue(Files.isDirectory(dirNoPid));
        assertTrue(Files.isDirectory(chatDir));
    }

    @Test
    void clearPidFileRemovesFile() throws Exception {
        Process p = spawnSleeper();
        Path dir = sessionDir("p1", "s-clear");
        WorkspaceReconciler.writePidFile(dir, p);
        assertTrue(Files.exists(dir.resolve(WorkspaceReconciler.PID_FILE)));
        WorkspaceReconciler.clearPidFile(dir);
        assertFalse(Files.exists(dir.resolve(WorkspaceReconciler.PID_FILE)));
        WorkspaceReconciler.clearPidFile(null); // null 安全
        com.devmind.common.agent.runtime.ProcessHelper.killTree(p);
        p.waitFor(10, TimeUnit.SECONDS);
    }

    @Test
    void pidReuseIsNotKilled() throws Exception {
        // 伪造 pid 文件：指向一个存活但启动时刻与记录相差很远的进程（模拟 PID 复用）
        Process other = spawnSleeper();
        Path dir = sessionDir("p1", "s-stale");
        Files.writeString(dir.resolve(WorkspaceReconciler.PID_FILE),
                other.pid() + "\n1\n"); // 1970 年的启动时刻 → 与真实 startInstant 不符
        try {
            var report = new WorkspaceReconciler(root).reconcile();
            assertTrue(report.reaped().isEmpty(), "启动时刻不吻合不得误杀");
            assertTrue(other.isAlive());
            assertEquals(1, report.ownerlessDirs().size());
        } finally {
            com.devmind.common.agent.runtime.ProcessHelper.killTree(other);
            other.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
