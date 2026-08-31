package com.devmind.session.proc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Windows 进程辅助：直接 destroy 杀不掉 claude 的 node 子进程树，
 * 统一用 taskkill /F /T 整树杀灭。
 */
public final class ProcessHelper {

    private static final Logger log = LoggerFactory.getLogger(ProcessHelper.class);

    private ProcessHelper() {
    }

    /**
     * 整树强杀。Windows 用 taskkill /F /T /PID，其他平台 destroyForcibly。
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
                log.warn("taskkill 失败，回退 destroyForcibly: pid={} err={}", pid, e.getMessage());
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
