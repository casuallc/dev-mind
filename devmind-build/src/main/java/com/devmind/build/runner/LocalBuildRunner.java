package com.devmind.build.runner;

import com.devmind.build.config.BuildProperties;
import com.devmind.build.model.BuildStep;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * CAP-08 FR-01 本地执行器：在项目仓库目录下逐步骤执行 shell 脚本。
 * 每步命令写临时 .sh 由 {@link BuildProperties#getShell()} 执行，stdout/stderr 双流实时回传，
 * 注入 BUILD_PROJECT_ID/BUILD_COMMIT/BUILD_BRANCH/BUILD_STEP 环境变量作为构建上下文（FR-03），
 * 单步超时 kill（FR-06 MVP 固定默认值）。
 */
@Component
public class LocalBuildRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalBuildRunner.class);

    private final BuildProperties props;

    public LocalBuildRunner(BuildProperties props) {
        this.props = props;
    }

    public StepResult runStep(Path repoPath, BuildStep step, String commit, String branch,
                              String projectId, Consumer<String> sink) {
        Path cwd = repoPath;
        if (step.workingDir() != null && !step.workingDir().isBlank()) {
            cwd = repoPath.resolve(step.workingDir()).normalize();
            if (!Files.isDirectory(cwd)) {
                return new StepResult(false, -1, "工作目录不存在: " + cwd);
            }
        }
        Path tmp = null;
        Process p = null;
        try {
            tmp = Files.createTempFile("devmind-build-", ".sh");
            Files.writeString(tmp, step.command(), StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(props.getShell(), tmp.toAbsolutePath().toString());
            pb.directory(cwd.toFile());
            Map<String, String> env = new HashMap<>();
            env.put("BUILD_PROJECT_ID", projectId == null ? "" : projectId);
            env.put("BUILD_COMMIT", commit == null ? "" : commit);
            env.put("BUILD_BRANCH", branch == null ? "" : branch);
            env.put("BUILD_STEP", step.name() == null ? "" : step.name());
            pb.environment().putAll(env);
            Process proc = pb.start();
            p = proc;
            Thread outT = new Thread(() -> pump(proc.getInputStream(), sink), "build-out");
            Thread errT = new Thread(() -> pump(proc.getErrorStream(), line -> sink.accept("[stderr] " + line)), "build-err");
            outT.start();
            errT.start();
            boolean finished = p.waitFor(props.getStepTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                return new StepResult(false, -1, "步骤超时（>" + props.getStepTimeoutMs() / 1000 + "s）：" + step.name());
            }
            outT.join(5000);
            errT.join(5000);
            return new StepResult(p.exitValue() == 0, p.exitValue(), null);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepResult(false, -1, "本地执行失败: " + rootMessage(e));
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
