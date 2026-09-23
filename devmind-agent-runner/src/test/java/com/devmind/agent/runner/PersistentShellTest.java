package com.devmind.agent.runner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-59 持久 shell 行为面：env/cd 跨命令保持、exit 内建死壳自动重起、超时整树杀、
 * 取消收口 cancelled+130、哨兵剥离。真实 bash 集成测试（本机无 bash 时整组跳过——
 * 与 execShell 部署前提一致：节点必须有可用 bash）。
 */
class PersistentShellTest {

    @TempDir
    Path base;

    @BeforeAll
    static void assumeBash() {
        boolean ok;
        try {
            Process p = new ProcessBuilder("bash", "-c", "exit 0").start();
            ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "本机无可用 bash，跳过持久 shell 集成测试");
    }

    private PersistentShell start() throws Exception {
        PersistentShell shell = new PersistentShell("bash", base);
        shell.ensureStarted(base);
        return shell;
    }

    @Test
    void envAndCdPersistAcrossCommands() throws Exception {
        Files.createDirectory(base.resolve("subdir"));
        PersistentShell shell = start();
        // env 保持：CAP-58 单条进程模型下第二条 echo $FOO 必为空
        shell.exec("export FOO=bar", 10);
        PersistentShell.Outcome o1 = shell.exec("echo $FOO", 10);
        assertEquals(0, o1.exitCode());
        assertTrue(o1.stdout().contains("bar"), o1.stdout());
        // cd 保持：哨兵带回新 cwd，下条命令仍在其中
        PersistentShell.Outcome o2 = shell.exec("cd subdir", 10);
        assertEquals(0, o2.exitCode());
        assertTrue(o2.cwd() != null && o2.cwd().replace('\\', '/').endsWith("subdir"), o2.cwd());
        PersistentShell.Outcome o3 = shell.exec("pwd", 10);
        assertTrue(o3.stdout().replace('\\', '/').contains("subdir"), o3.stdout());
        shell.destroyQuietly();
    }

    @Test
    void sentinelsStrippedFromOutput() throws Exception {
        PersistentShell shell = start();
        PersistentShell.Outcome o = shell.exec("echo hello", 10);
        assertEquals(0, o.exitCode());
        assertEquals("hello", o.stdout().strip());
        assertFalse(o.stdout().contains(PersistentShell.DONE_PREFIX));
        assertFalse(o.stdout().contains(PersistentShell.CWD_PREFIX));
        shell.destroyQuietly();
    }

    @Test
    void nonZeroExitCapturedAndShellSurvives() throws Exception {
        PersistentShell shell = start();
        PersistentShell.Outcome o = shell.exec("ls /nonexistent-devmind-path", 10);
        assertTrue(o.exitCode() != 0);
        assertFalse(o.stderr().isBlank());
        assertFalse(o.shellDied());
        assertEquals(0, shell.exec("echo alive", 10).exitCode());
        shell.destroyQuietly();
    }

    @Test
    void exitBuiltinKillsShellAndRespawnWorks() throws Exception {
        PersistentShell shell = start();
        PersistentShell.Outcome o = shell.exec("exit 3", 10);
        assertTrue(o.shellDied());
        assertFalse(shell.isAlive());
        // 重启后 env 丢失但可用（TerminalHandler 负责重 cd）
        shell.ensureStarted(base);
        assertEquals(0, shell.exec("echo back", 10).exitCode());
        shell.destroyQuietly();
    }

    @Test
    void timeoutKillsWholeShell() throws Exception {
        PersistentShell shell = start();
        PersistentShell.Outcome o = shell.exec("sleep 30", 1);
        assertTrue(o.timedOut());
        assertFalse(shell.isAlive());
        shell.destroyQuietly();
    }

    @Test
    void cancelFinishesRunningCommandWith130() throws Exception {
        PersistentShell shell = start();
        AtomicReference<PersistentShell.Outcome> ref = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                ref.set(shell.exec("sleep 30", 60));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        Thread.sleep(1000); // 等命令进入执行
        shell.cancel();
        t.join(15_000);
        PersistentShell.Outcome o = ref.get();
        assertTrue(o != null, "exec 未在 cancel 后收口");
        assertTrue(o.cancelled());
        assertEquals(130, o.exitCode());
        assertFalse(shell.isAlive());
        // 取消后自动重启可用
        shell.ensureStarted(base);
        assertEquals(0, shell.exec("echo again", 10).exitCode());
        shell.destroyQuietly();
    }
}
