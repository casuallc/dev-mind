package com.devmind.agent.runner;

import com.devmind.common.agent.exec.RunnerWorkspace;
import com.devmind.common.agent.runtime.ProcessHelper;
import com.devmind.common.program.ProgramContract;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
 *
 * <p>CAP-56：帧带 {@code bundle{kind,id}} 时先由 {@link LabBundlePuller} 拉执行包（脚本 + 数据）
 * 解到临时目录，再以本类注入的 {@link #SCRIPT_ENV}/{@link #PAYLOAD_ENV} 环境变量跑命令
 * （服务端渲染的命令行按变量名引用）；<b>拉包失败即该步骤失败</b>，不降级跑一个空脚本。</p>
 */
public class ExecHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecHandler.class);

    /** CAP-56 入口脚本绝对路径的环境变量名（服务端渲染命令行时按名引用，改名要两边同步） */
    public static final String SCRIPT_ENV = "DEVMIND_LAB_SCRIPT";

    /** CAP-56 数据文件绝对路径的环境变量名（同上） */
    public static final String PAYLOAD_ENV = "DEVMIND_LAB_PAYLOAD";

    /** 白名单豁免的 shell 内置命令/关键字（小写）；package 可见供 CAP-58 TerminalHandler 复用 */
    static final Set<String> SHELL_BUILTINS = Set.of(
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
        Path bundleDir = null;
        TestProgramPuller.Materialized program = null;
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

            // CAP-56：带 bundle 块 = 决策实验室评测/微调步骤——先拉执行包（脚本 + 数据）再跑。
            // 白名单已在上一步拦掉不该跑的命令，拉包（有网络开销）放在其后。
            JsonNode bundleNode = frame.path("bundle");
            if (bundleNode.isObject()) {
                String kind = bundleNode.path("kind").asText("");
                String bundleId = bundleNode.path("id").asText("");
                if (kind.isBlank() || bundleId.isBlank()) {
                    throw new IllegalStateException("bundle 块缺 kind/id");
                }
                if (ProgramContract.KIND.equals(kind)) {
                    // CAP-104 M1.5：程序包树形解包 + 契约 v1 输入/输出路径注入
                    program = TestProgramPuller.pull(config, bundleId);
                    bundleDir = program.dir();
                    prepareProgramInput(program, env);
                    emit(execId, "stdout", "[runner] 程序包已解包: " + program.fileName()
                            + " (" + program.manifest().name() + " " + program.manifest().version() + ")"
                            + " → " + program.dir(), token);
                    // CAP-106 FR-07：执行期探测——预检（CAP-104 M1.5）可能因 env 覆盖放行，
                    // 这里按合并后 env 做最终判定，不满足则不跑入口（[toolchain] 前缀由服务端
                    // 映射 BLOCKED 语义，而不是 INFRA 错误）
                    String tcKey = program.manifest().requiredToolchainKey();
                    if (tcKey != null) {
                        String found = new com.devmind.common.agent.exec.ToolchainDetector()
                                .detectOne(tcKey, config.toolchainPaths(), env);
                        if (found == null) {
                            throw new IllegalStateException("[toolchain] 执行期探测 " + tcKey
                                    + " 失败：合并 env 后仍未找到该运行时（程序包 runtime="
                                    + program.manifest().runtime() + "，env PATH=" + env.getOrDefault("PATH", "<未覆盖>")
                                    + "）。请检查节点页环境变量或 agent.properties 的 toolchain." + tcKey);
                        }
                        if (!program.manifest().satisfiesRuntimeVersion(found)) {
                            throw new IllegalStateException("[toolchain] 执行期探测 " + tcKey + " 版本 " + found
                                    + " 不满足程序包 runtimeVersion=" + program.manifest().runtimeVersion());
                        }
                        emit(execId, "stdout", "[runner] 执行期探测通过: " + tcKey + " " + found, token);
                    }
                } else {
                    LabBundlePuller.Materialized bundle = LabBundlePuller.pull(config, kind, bundleId);
                    bundleDir = bundle.dir();
                    env.put(SCRIPT_ENV, bundle.script().toAbsolutePath().toString());
                    env.put(PAYLOAD_ENV, bundle.payload().toAbsolutePath().toString());
                    emit(execId, "stdout", "[runner] 执行包已物化: " + bundle.fileName()
                            + " → " + bundle.dir(), token);
                }
            }

            // 工作区：程序包以解包根为工作区；带 repo 块 = 构建；否则 runner 本地目录（部署/发版）
            Path cwd;
            JsonNode repoNode = frame.path("repo");
            if (program != null) {
                cwd = program.root();
            } else if (repoNode.isObject() && !repoNode.path("remoteUrl").asText("").isBlank()) {
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
                // 纯 exec 用途的 runner 可能从未建过 workDir（无会话/构建流量），
                // ProcessBuilder 要求 cwd 必须存在（否则 Windows error=267）
                Files.createDirectories(cwd);
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
                // CAP-43：节点代理命中 exec scope → exec 脚本进程走代理（脚本内 curl/git 等
                // 外网流量）。大小写四件全注入；帧 env 已有同名键不覆盖（帧 env 优先）
                String execProxy = com.devmind.common.agent.exec.NodeProxy.urlFor("exec");
                if (execProxy != null) {
                    for (String key : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"}) {
                        pb.environment().putIfAbsent(key, execProxy);
                    }
                }
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
                if (program != null) {
                    try {
                        collectProgram(execId, program, token);
                    } catch (Exception e) {
                        emit(execId, "stderr", "[runner] 程序产物回收失败: " + e.getMessage(), token);
                    }
                }
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
            // 执行包临时目录：脚本与数据都在里面，跑完即弃（权重由 --out 指向服务端指定的持久路径，不在这）
            LabBundlePuller.deleteQuietly(bundleDir);
            permits.release();
        }
    }

    /**
     * execAllowlist 二次过滤（服务端只下发项目预定义步骤之外的本机防线）：
     * 白名单空 = 拒绝一切；逐行取首 token 校验前缀命中，shell 内置命令/关键字豁免。
     */
    void checkAllowlist(String command) {
        checkAllowlist(config.execAllowlist(), command);
    }

    /** 静态版（CAP-73 VerifyHandler 复用同一校验语义——构建步骤/补丁钩子与 exec 同一防线）。 */
    static void checkAllowlist(List<String> allowed, String command) {
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

    /** workingDir 相对基准目录解析（越界防护 + 必须已存在）。静态化供 CAP-73 VerifyHandler 复用。 */
    static Path resolveCwd(Path base, String workingDir) {
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

    /**
     * 程序契约 v1 输入落盘：服务端把 input.json 以 base64 放在 {@code DEVMIND_PROGRAM_INPUT_B64}
     * （避免把 JSON 拼进 shell 命令/临时脚本），这里落盘后移除该变量，只给程序暴露三个绝对路径。
     */
    private void prepareProgramInput(TestProgramPuller.Materialized program, Map<String, String> env)
            throws IOException {
        String b64 = env.remove(ProgramContract.INPUT_B64_ENV);
        byte[] input;
        try {
            input = b64 == null ? null : Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("程序输入 base64 非法", e);
        }
        if (input == null || input.length == 0) {
            throw new IllegalStateException("程序型用例缺少输入 JSON（" + ProgramContract.INPUT_B64_ENV + "）");
        }
        if (input.length > ProgramContract.MAX_INPUT_BYTES) {
            throw new IllegalStateException("程序输入超限（" + input.length + " > "
                    + ProgramContract.MAX_INPUT_BYTES + " 字节）");
        }
        Path root = program.root();
        Path inputPath = root.resolve(ProgramContract.INPUT_PATH).normalize();
        Path resultPath = root.resolve(ProgramContract.RESULT_PATH).normalize();
        Path artifacts = root.resolve(ProgramContract.ARTIFACTS_PATH).normalize();
        if (!inputPath.startsWith(root) || !resultPath.startsWith(root) || !artifacts.startsWith(root)) {
            throw new IllegalStateException("程序契约路径逃逸包根");
        }
        Files.createDirectories(inputPath.getParent());
        Files.createDirectories(artifacts);
        Files.write(inputPath, input);
        try {
            Files.setPosixFilePermissions(inputPath,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
        } catch (Exception ignored) {
            // Windows/非 POSIX 文件系统不支持只读权限位；输入不落敏感信息，尽力而为
        }
        env.put(ProgramContract.INPUT_ENV, inputPath.toAbsolutePath().toString());
        env.put(ProgramContract.RESULT_ENV, resultPath.toAbsolutePath().toString());
        env.put(ProgramContract.ARTIFACTS_ENV, artifacts.toAbsolutePath().toString());
    }

    /** 进程结束后回收 result.json 与 artifacts（按 512KB WS 上限分片上行），随后调用方删临时目录。 */
    private void collectProgram(String execId, TestProgramPuller.Materialized program, String token)
            throws IOException {
        Path root = program.root();
        Path result = root.resolve(ProgramContract.RESULT_PATH).normalize();
        if (Files.isRegularFile(result)) {
            long size = Files.size(result);
            if (size > ProgramContract.MAX_RESULT_BYTES) {
                emit(execId, "stderr", "[runner] result.json 超限（" + size + " > "
                        + ProgramContract.MAX_RESULT_BYTES + " 字节），按畸形结果处理", token);
            } else {
                byte[] bytes = Files.readAllBytes(result);
                emit(execId, "stdout", ProgramContract.RESULT_MARKER + bytes.length + " "
                        + TestProgramPuller.sha256(bytes), token);
                for (int off = 0, seq = 0; off < bytes.length; off += ProgramContract.CHUNK_BYTES, seq++) {
                    int end = Math.min(bytes.length, off + ProgramContract.CHUNK_BYTES);
                    emit(execId, "stdout", ProgramContract.RESULT_CHUNK_MARKER + seq + " "
                            + Base64.getEncoder().encodeToString(
                                    java.util.Arrays.copyOfRange(bytes, off, end)), token);
                }
            }
        }

        Path artifacts = root.resolve(ProgramContract.ARTIFACTS_PATH).normalize();
        if (!Files.isDirectory(artifacts)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(artifacts)) {
            files = walk.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> relative(artifacts, p)))
                    .toList();
        }
        int count = 0;
        long total = 0;
        for (Path file : files) {
            String rel = relative(artifacts, file);
            if (count >= ProgramContract.MAX_ARTIFACTS) {
                emit(execId, "stderr", "[runner] artifacts 数量超限（>" + ProgramContract.MAX_ARTIFACTS
                        + "），其余未采集", token);
                break;
            }
            long size = Files.size(file);
            if (size > ProgramContract.MAX_ARTIFACT_BYTES) {
                emit(execId, "stderr", "[runner] artifact 超限跳过: " + rel + "（" + size + " > "
                        + ProgramContract.MAX_ARTIFACT_BYTES + " 字节）", token);
                continue;
            }
            if (total + size > ProgramContract.MAX_ARTIFACTS_TOTAL_BYTES) {
                emit(execId, "stderr", "[runner] artifacts 总量超限（>"
                        + ProgramContract.MAX_ARTIFACTS_TOTAL_BYTES + " 字节），其余未采集", token);
                break;
            }
            byte[] bytes = Files.readAllBytes(file);
            String name = Base64.getEncoder().encodeToString(rel.getBytes(StandardCharsets.UTF_8));
            emit(execId, "stdout", ProgramContract.ARTIFACT_MARKER + name + " " + bytes.length + " "
                    + TestProgramPuller.sha256(bytes), token);
            for (int off = 0, seq = 0; off < bytes.length; off += ProgramContract.CHUNK_BYTES, seq++) {
                int end = Math.min(bytes.length, off + ProgramContract.CHUNK_BYTES);
                emit(execId, "stdout", ProgramContract.ARTIFACT_CHUNK_MARKER + name + " " + seq + " "
                        + Base64.getEncoder().encodeToString(
                                java.util.Arrays.copyOfRange(bytes, off, end)), token);
            }
            count++;
            total += bytes.length;
        }
    }

    private String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
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
