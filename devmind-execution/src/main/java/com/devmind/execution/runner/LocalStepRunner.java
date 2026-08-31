package com.devmind.execution.runner;

import com.devmind.execution.config.ExecutionProperties;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 本地执行器（P0-1 统一执行底座，自 CAP-08 LocalBuildRunner 泛化）：在指定目录下执行 shell 脚本。
 * 每步命令写临时 .sh 由 {@link ExecutionProperties#getShell()} 执行，stdout/stderr 双流实时回传；
 * 环境变量由调用方按业务注入（如构建传 BUILD_PROJECT_ID/BUILD_COMMIT/…），单步超时 kill。
 */
@Component
public class LocalStepRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalStepRunner.class);

    private final ExecutionProperties props;

    public LocalStepRunner(ExecutionProperties props) {
        this.props = props;
    }

    /**
     * @param baseDir 基准目录（step.workingDir 相对它解析）
     * @param env     注入子进程的环境变量（key 原样传递）
     */
    public StepResult runStep(Path baseDir, StepSpec step, Map<String, String> env, Consumer<String> sink) {
        Path cwd = baseDir;
        if (step.workingDir() != null && !step.workingDir().isBlank()) {
            cwd = baseDir.resolve(step.workingDir()).normalize();
            if (!Files.isDirectory(cwd)) {
                return StepResult.failed(-1, "工作目录不存在: " + cwd);
            }
        }
        Path tmp = null;
        Process p = null;
        try {
            tmp = Files.createTempFile("devmind-exec-", ".sh");
            Files.writeString(tmp, step.command(), StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(props.getShell(), tmp.toAbsolutePath().toString());
            pb.directory(cwd.toFile());
            if (env != null) {
                env.forEach((k, v) -> pb.environment().put(k, v == null ? "" : v));
            }
            Process proc = pb.start();
            p = proc;
            Thread outT = new Thread(() -> pump(proc.getInputStream(), sink), "exec-out");
            Thread errT = new Thread(() -> pump(proc.getErrorStream(), line -> sink.accept("[stderr] " + line)), "exec-err");
            outT.start();
            errT.start();
            boolean finished = p.waitFor(props.getStepTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                return StepResult.failed(-1, "步骤超时（>" + props.getStepTimeoutMs() / 1000 + "s）：" + step.name());
            }
            outT.join(5000);
            errT.join(5000);
            return new StepResult(p.exitValue() == 0, p.exitValue(), null);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.failed(-1, "本地执行失败: " + rootMessage(e));
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) { }
            }
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            }
        }
    }

    private void pump(InputStream in, Consumer<String> sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    sink.accept(line);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
