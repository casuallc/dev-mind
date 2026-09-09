package com.devmind.agent.runner;

import com.devmind.common.agent.exec.RunnerWorkspace;
import com.devmind.common.agent.runtime.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * CAP-36 exec 帧 handler（runner 侧本地执行代理）：收 exec{execId, command, ...} →
 * execAllowlist 二次校验 → 可选准备构建工作区（repo 块，复用 {@link RunnerWorkspace} 克隆缓存）→
 * 命令写临时 .sh 以 execShell 拉起 → stdout/stderr 行流式回 exec_log 帧 → exec_exit 收口。
 *
 * <p><b>安全</b>：双层校验的 runner 层——execAllowlist 为空 = 拒绝一切 exec；
 * 逐行校验脚本每个非空非注释行的首 token 须命中白名单前缀（shell 内置命令 echo/cd/if 等豁免）。
 * token 仅内存：repo.token 只进 git 进程参数，所有上行日志经 {@link RunnerWorkspace#sanitize} 脱敏。</p>
 *
 * <p><b>并发</b>：许可数 = maxConcurrent（与会话共享同一上限语义）；超额在虚拟线程内排队等许可。</p>
 */
public class ExecHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecHandler.class);

    /** 白名单豁免的 shell 内置命令/关键字（小写） */
    private static final Set<String> SHELL_BUILTINS = Set.of(
            "echo", "cd", "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "export", "set", "unset", "exit", "return", "test", "[", "[[", "local", "readonly", "shift",
            "source", ".", "true", "false", ":", "wait", "trap", "umask", "pwd", "pushd", "popd", "let",
            "declare", "typeset", "eval", "exec", "break", "continue", "function", "select", "until", "in", "{", "}", "!");

    private final RunnerConfig config;
    private final RunnerWorkspace workspace;
    private final Consumer<Map<String, Object>> sender;
    /** execId → 运行中的进程（shutdown killAll 用） */
    private final Map<String, Process> active = new ConcurrentHashMap<>();
    /** 进行中的构建工作区 id（GC 跳过用） */
    private final Set<String> activeBuilds = ConcurrentHashMap.newKeySet();
    private final Semaphore permits;

    public ExecHandler(RunnerConfig config, RunnerWorkspace workspace, Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.workspace = workspace;
        this.sender = sender;
        this.permits = new Semaphore(Math.max(1, config.maxConcurrent()));
    }

    /** WS listener 线程入口：立即投虚拟线程，帧处理不阻塞收帧。 */
    public void handle(JsonNode frame) {
        Thread.ofVirtual().name("exec-" + frame.path("execId").asText("?")).start(() -> run(frame));
    }

    /** 进行中的构建工作区 id 集（WorkspaceGc.sweepBuilds 跳过用）。 */
    public Set<String> activeBuildWorkspaceIds() {
        return Set.copyOf(activeBuilds);
    }

    /** shutdown hook：整树杀光运行中的 exec 进程。 */
    public void killAll() {
        for (Process p : active.values()) {
            ProcessHelper.killTree(p);
        }
    }

    private void run(JsonNode frame) {
        String execId = frame.path("execId").asText("");
        if (execId.isBlank()) {
            log.warn("exec 帧缺 execId，丢弃");
            return;
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String token = null;
        try {
            String command = frame.path("command").asText("");
            String projectId = frame.path("projectId").asText(null);
            String workspaceId = frame.path("workspaceId").asText(null);
            String workingDir = frame.path("workingDir").asText(null);
            long timeoutSec = frame.path("timeoutSec").asLong(1800);
            Map<String, String> env = new HashMap<>();
            JsonNode envNode = frame.path("env");
            if (envNode.isObject()) {
                envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
            }

            checkAllowlist(command);

            // 工作区：带 repo 块 = 构建（clone 缓存 + detach checkout）；否则 runner 本地目录（部署/发版）
            Path cwd;
            JsonNode repoNode = frame.path("repo");
            if (repoNode.isObject() && !repoNode.path("remoteUrl").asText("").isBlank()) {
                if (projectId == null || projectId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
                    throw new IllegalStateException("带 repo 块的 exec 必须携带 projectId 与 workspaceId");
                }
                token = repoNode.path("token").asText(null);
                activeBuilds.add(workspaceId);
                cwd = workspace.prepareBuild(workspaceId, projectId,
                        repoNode.path("remoteUrl").asText(""),
                        repoNode.path("branch").asText(""),
                        repoNode.path("commit").asText(""), token);
                emit(execId, "stdout", "[runner] 构建工作区就绪: " + cwd, token);
            } else {
                cwd = config.resolveWorkDir(projectId);
            }
            cwd = resolveCwd(cwd, workingDir);

            int code;
            boolean timedOut = false;
            Path tmp = null;
            Process proc = null;
            try {
                tmp = Files.createTempFile("devmind-exec-", ".sh");
                Files.writeString(tmp, command, StandardCharsets.UTF_8);
                ProcessBuilder pb = new ProcessBuilder(config.execShell(), tmp.toAbsolutePath().toString());
                pb.directory(cwd.toFile());
                env.forEach((k, v) -> pb.environment().put(k, v == null ? "" : v));
                proc = pb.start();
                active.put(execId, proc);
                String tk = token;
                Process p = proc;
                Thread outT = Thread.ofVirtual().name("exec-out-" + execId)
                        .start(() -> pump(p.getInputStream(), "stdout", execId, tk));
                Thread errT = Thread.ofVirtual().name("exec-err-" + execId)
                        .start(() -> pump(p.getErrorStream(), "stderr", execId, tk));
                if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    timedOut = true;
                    emit(execId, "stderr", "[runner] 步骤超时（>" + timeoutSec + "s），整树终止", tk);
                    ProcessHelper.killTree(proc);
                    proc.waitFor(10, TimeUnit.SECONDS);
                }
                outT.join(5000);
                errT.join(5000);
                code = proc.exitValue();
            } finally {
                active.remove(execId);
                if (proc != null && proc.isAlive()) {
                    ProcessHelper.killTree(proc);
                }
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                    }
                }
            }
            Map<String, Object> exit = new LinkedHashMap<>();
            exit.put("type", "exec_exit");
            exit.put("execId", execId);
            exit.put("code", code);
            if (timedOut) {
                exit.put("timedOut", true);
            }
            sender.accept(exit);
            log.info("exec 收口: execId={} code={} timedOut={}", execId, code, timedOut);
        } catch (Exception e) {
            log.warn("exec 失败: execId={} err={}", execId, e.getMessage());
            emit(execId, "stderr", "[runner] " + e.getMessage(), token);
            Map<String, Object> exit = new LinkedHashMap<>();
            exit.put("type", "exec_exit");
            exit.put("execId", execId);
            exit.put("code", -1);
            exit.put("error", String.valueOf(e.getMessage()));
            sender.accept(exit);
        } finally {
            String workspaceId = frame.path("workspaceId").asText(null);
            if (workspaceId != null && !workspaceId.isBlank()) {
                activeBuilds.remove(workspaceId);
            }
            permits.release();
        }
    }

    /**
     * execAllowlist 二次过滤（服务端只下发项目预定义步骤之外的本机防线）：
     * 白名单空 = 拒绝一切；逐行取首 token 校验前缀命中，shell 内置命令/关键字豁免。
     */
    void checkAllowlist(String command) {
        List<String> allowed = config.execAllowlist();
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalStateException("runner 未配置 execAllowlist，拒绝一切 exec 指令");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("exec 帧 command 为空");
        }
        List<String> offenders = new ArrayList<>();
        for (String line : command.split("\\R")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            String first = stripped.split("\\s+", 2)[0];
            String lower = first.toLowerCase();
            if (SHELL_BUILTINS.contains(lower)) {
                continue;
            }
            boolean hit = allowed.stream().anyMatch(a -> first.equals(a) || first.startsWith(a));
            if (!hit) {
                offenders.add(first);
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("命令不在 execAllowlist 白名单: " + String.join(", ", offenders)
                    + "（允许前缀: " + String.join(", ", allowed) + "）");
        }
    }

    /** workingDir 相对基准目录解析（越界防护 + 必须已存在）。 */
    private Path resolveCwd(Path base, String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return base;
        }
        Path cwd = base.resolve(workingDir).normalize();
        if (!cwd.startsWith(base)) {
            throw new IllegalStateException("workingDir 越界（.. 逃逸防护）: " + workingDir);
        }
        if (!Files.isDirectory(cwd)) {
            throw new IllegalStateException("工作目录不存在: " + cwd);
        }
        return cwd;
    }

    private void pump(InputStream in, String stream, String execId, String token) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                emit(execId, stream, line, token);
            }
        } catch (IOException ignored) {
        }
    }

    private void emit(String execId, String stream, String chunk, String token) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exec_log");
        frame.put("execId", execId);
        frame.put("stream", stream);
        frame.put("chunk", RunnerWorkspace.sanitize(chunk, token));
        try {
            sender.accept(frame);
        } catch (Exception e) {
            log.debug("exec_log 发送失败: {}", e.getMessage());
        }
    }
}
