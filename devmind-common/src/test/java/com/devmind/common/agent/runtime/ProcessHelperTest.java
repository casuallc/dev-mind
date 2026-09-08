package com.devmind.common.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessHelper#killTree} 整树杀灭断言：spawn 祖孙进程，killTree 后主进程与子孙全灭；
 * {@link ProcessHelper#killTreeByPid} 对死 pid 返回 false、对活 pid 返回 true 并杀光子孙。
 */
class ProcessHelperTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 跨平台 spawn 一个带子进程的常驻父进程。注意 Windows 不能用 timeout（stdin 被重定向即报错退出），用 ping 代替。 */
    private static Process spawnParentWithChild() throws Exception {
        ProcessBuilder pb = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "start /b ping -n 300 127.0.0.1 >nul & ping -n 300 127.0.0.1 >nul")
                : new ProcessBuilder("sh", "-c", "sleep 300 & sleep 300 & wait");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    @Test
    void killTreeKillsDescendants() throws Exception {
        Process parent = spawnParentWithChild();
        long parentPid = parent.pid();
        // 等子进程起来
        TimeUnit.SECONDS.sleep(2);
        assertTrue(parent.isAlive());
        assertTrue(ProcessHandle.of(parentPid).map(h -> h.descendants().findAny().isPresent()).orElse(false),
                "前置：父进程应有子进程");

        ProcessHelper.killTree(parent);
        parent.waitFor(10, TimeUnit.SECONDS);
        assertFalse(parent.isAlive());
        assertTrue(ProcessHandle.of(parentPid).map(h -> h.descendants().findAny().isEmpty()).orElse(true),
                "killTree 后子孙应全灭");
    }

    @Test
    void killTreeByPidReapsOrphan() throws Exception {
        Process parent = spawnParentWithChild();
        TimeUnit.SECONDS.sleep(2);
        long pid = parent.pid();

        assertTrue(ProcessHelper.killTreeByPid(pid));
        parent.waitFor(10, TimeUnit.SECONDS);
        assertFalse(parent.isAlive());
        assertTrue(ProcessHandle.of(pid).map(h -> h.descendants().findAny().isEmpty()).orElse(true),
                "killTreeByPid 后子孙应全灭");
    }

    @Test
    void killTreeByPidReturnsFalseForDeadPid() throws Exception {
        Process p = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "echo x").start()
                : new ProcessBuilder("sh", "-c", "echo x").start();
        p.waitFor(10, TimeUnit.SECONDS);
        assertFalse(ProcessHelper.killTreeByPid(p.pid()));
    }
}
