package com.devmind.common.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 进程树整杀辅助：直接 destroy 杀不掉 claude 的 node 子进程树。
 * Windows 用 taskkill /F /T 整树杀灭（失败兜底 ProcessHandle 子孙遍历）；
 * 其他平台用 ProcessHandle.descendants() 先杀子孙再杀主进程。
 */
public final class ProcessHelper {

    private static final Logger log = LoggerFactory.getLogger(ProcessHelper.class);

    private ProcessHelper() {
    }

    /**
     * 整树强杀。Windows 用 taskkill /F /T /PID，其他平台先杀子孙再 destroyForcibly 主进程。
     */
    public static void killTree(Process process) {
        if (process == null) {
            return;
        }
        long pid = process.pid();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
                pb.redirectErrorStream(true);
                Process killer = pb.start();
                if (killer.waitFor(10, TimeUnit.SECONDS)) {
                    log.info("taskkill 完成: pid={}", pid);
                } else {
                    log.warn("taskkill 超时: pid={}", pid);
                    killer.destroyForcibly();
                }
            } catch (Exception e) {
                log.warn("taskkill 失败，回退子孙遍历: pid={} err={}", pid, e.getMessage());
                killDescendants(process.toHandle());
            }
        } else {
            killDescendants(process.toHandle());
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * 按 pid 整树强杀（runner 重启对账回收孤儿进程用）。
     *
     * @return 命中存活进程并触发杀灭返回 true；pid 不存在/已死返回 false
     */
    public static boolean killTreeByPid(long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        ProcessHandle h = handle.get();
        killDescendants(h);
        h.destroyForcibly();
        log.info("killTreeByPid 完成: pid={}", pid);
        return true;
    }

    /** 深度优先杀光子孙进程（快照遍历，忽略个别已退出的竞态）。 */
    private static void killDescendants(ProcessHandle handle) {
        handle.descendants().forEach(child -> {
            killDescendants(child);
            child.destroyForcibly();
        });
    }
}
