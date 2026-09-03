package com.devmind.agent.runner;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * FR-09 自升级收口进程（同一 shaded jar 内第二个 main 类，由 RunnerUpgrader 以
 * {@code java -cp <newJar> SelfUpdater <targetJar> <newJar> <configFile>} 拉起）。
 *
 * <p>Windows 运行中的 jar 不可覆盖，故替换发生在旧 runner 退出后：轮询等文件锁释放
 * （最多 60s）→ target→.bak → .new→target → 以相同参数 {@code java -jar} 重启。
 * 新 runner 输出 append 到 jar 旁 runner.log；本进程诊断写 runner-update.log。</p>
 */
public class SelfUpdater {

    private static final int MAX_ATTEMPTS = 120;
    private static final long RETRY_MS = 500;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SelfUpdater <targetJar> <newJar> <configFile>");
            System.exit(2);
        }
        Path target = Path.of(args[0]);
        Path newJar = Path.of(args[1]);
        Path configFile = Path.of(args[2]);
        Path updateLog = target.resolveSibling("runner-update.log");

        try (PrintWriter log = new PrintWriter(new FileWriter(updateLog.toFile(),
                StandardCharsets.UTF_8, true))) {
            log(log, "SelfUpdater 启动: target=" + target + " new=" + newJar);

            if (!waitAndReplace(target, newJar, log)) {
                log(log, "等待文件锁释放超时（60s），放弃替换；保留 .new/.bak 现场，请人工处理");
                System.exit(1);
            }
            log(log, "替换完成，重启 runner: " + target);

            new ProcessBuilder(RunnerUpgrader.javaBin(), "-jar",
                    target.toAbsolutePath().toString(), configFile.toAbsolutePath().toString())
                    .directory(target.toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            target.resolveSibling("runner.log").toFile()))
                    .start();
            log(log, "runner 已重启，SelfUpdater 退出");
        }
    }

    /** 轮询替换：每一步独立可重入（.bak 已存在而 target 不在 = 上次断点，直接续做第二步）。 */
    private static boolean waitAndReplace(Path target, Path newJar, PrintWriter log) {
        Path bak = target.resolveSibling(target.getFileName() + ".bak");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try {
                if (Files.exists(target)) {
                    RunnerUpgrader.move(target, bak);
                    log(log, "旧包已备份: " + bak);
                }
                RunnerUpgrader.move(newJar, target);
                return true;
            } catch (FileSystemException e) {
                // 文件仍被旧进程占用，稍后重试
                sleep();
            } catch (IOException e) {
                log(log, "替换失败: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(PrintWriter log, String msg) {
        log.println("[" + LocalDateTime.now() + "] " + msg);
        log.flush();
    }
}
