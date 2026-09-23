package com.devmind.agent.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * CAP-57 proc 帧 handler（协议 v15）：服务实例（分类边车等长驻进程）的起停管控。
 *
 * <p>安全模型（与帧模型 javadoc 同口径）：server→runner 通道本就承载 upgrade 帧（能推任意 jar），
 * proc 帧信任等级不高于它；真正的防线是<b>目录收容</b>——帧里 workdir/pidFile/logFile 一律
 * 相对 {@code <workspaceRoot>/classify/} 根，归一化后越界即 ack 拒绝，runner 不执行收容根
 * 之外的任何东西。</p>
 *
 * <p><b>argv 不过 shell</b>：{@code ProcessBuilder(argv)} 直接拉起（Windows 没有 bash/nohup，
 * shell 转义整类问题不存在）。日志 redirect 追加写 logFile，pid 落 pidFile + proc.json
 * （{@link ProcRegistry} 对账）。<b>不守护</b>：进程退出不自动拉起（v1 无 supervisor），
 * 实例崩溃由服务端健康轮询标红。</p>
 */
public class ProcHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcHandler.class);
    /** start 后确认进程没立即退出的观察窗（边车 import 失败通常毫秒级暴毙） */
    private static final long START_OBSERVE_MS = 800;
    /** stop 树杀后等待死透的上限 */
    private static final long KILL_WAIT_MS = 10_000;

    private final Path classifyRoot; // <workspaceRoot>/classify（绝对、归一化）
    private final ProcRegistry registry;
    private final Consumer<Map<String, Object>> sender;

    public ProcHandler(Path workspaceRoot, ProcRegistry registry, Consumer<Map<String, Object>> sender) {
        this.classifyRoot = workspaceRoot.toAbsolutePath().normalize().resolve("classify");
        this.registry = registry;
        this.sender = sender;
    }

    /** 帧入口（WS listener 线程）——动作本身耗时不定（stop 要等死透），一律虚拟线程异步 */
    public void handle(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String action = frame.path("action").asText("");
        String instanceId = frame.path("instanceId").asText("");
        List<String> argv = new ArrayList<>();
        frame.path("argv").forEach(a -> argv.add(a.asText("")));
        String command = frame.path("command").asText("");
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = frame.path("env");
        if (envNode.isObject()) {
            envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
        }
        String workdir = frame.path("workdir").asText("");
        String pidFile = frame.path("pidFile").asText("");
        String logFile = frame.path("logFile").asText("");
        Thread.ofVirtual().name("proc-" + action + "-" + instanceId).start(() -> {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "proc_ack");
            ack.put("requestId", requestId);
            ack.put("action", action);
            try {
                if (!instanceId.matches("[a-zA-Z0-9._-]+")) {
                    throw new IllegalStateException("非法 instanceId: " + instanceId);
                }
                switch (action) {
                    case "start" -> doStart(ack, instanceId, argv, command, env, workdir, pidFile, logFile);
                    case "stop" -> doStop(ack, instanceId, pidFile);
                    case "restart" -> {
                        doStop(ack, instanceId, pidFile);
                        if (Boolean.TRUE.equals(ack.get("ok"))) {
                            doStart(ack, instanceId, argv, command, env, workdir, pidFile, logFile);
                        }
                    }
                    case "status" -> doStatus(ack, instanceId, pidFile);
                    default -> throw new IllegalStateException("未知 proc action: " + action);
                }
            } catch (Exception e) {
                log.warn("受管进程操作失败: action={} instance={} err={}", action, instanceId, e.getMessage());
                ack.put("ok", false);
                ack.put("status", "UNKNOWN");
                ack.put("error", String.valueOf(e.getMessage()));
            }
            sender.accept(ack);
        });
    }

    // ---------------- actions ----------------

    private void doStart(Map<String, Object> ack, String instanceId, List<String> argv, String command,
                         Map<String, String> env, String workdirRel, String pidFileRel, String logFileRel)
            throws Exception {
        if (argv.isEmpty()) {
            throw new IllegalStateException("start 缺少 argv（服务端组帧缺失）");
        }
        Path workdir = contained(workdirRel, "workdir");
        Path pidFile = contained(pidFileRel, "pidFile");
        Path logFile = contained(logFileRel, "logFile");
        // 幂等：已活着的实例不重复拉起（两个进程抢一个端口比"多余的 ack"糟得多）
        Optional<Long> alive = alivePid(instanceId, pidFile);
        if (alive.isPresent()) {
            ack.put("ok", true);
            ack.put("status", "RUNNING");
            ack.put("pid", alive.get());
            ack.put("detail", "进程已在运行（幂等）");
            return;
        }
        if (!Files.isDirectory(workdir)) {
            throw new IllegalStateException("工作目录不存在（应用包未安装到本节点？）: " + workdir);
        }
        Files.createDirectories(pidFile.toAbsolutePath().getParent());
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workdir.toFile());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process proc = pb.start();
        long pid = proc.pid();
        Files.writeString(pidFile, String.valueOf(pid));
        registry.register(instanceId, pid, command);
        Thread.sleep(START_OBSERVE_MS);
        if (!proc.isAlive()) {
            registry.unregister(instanceId);
            Files.deleteIfExists(pidFile);
            throw new IllegalStateException("进程启动后即退出（exit=" + proc.exitValue()
                    + "），输出见节点日志: " + logFile);
        }
        log.info("受管进程已拉起: instance={} pid={} cmd={}", instanceId, pid, command);
        ack.put("ok", true);
        ack.put("status", "RUNNING");
        ack.put("pid", pid);
        ack.put("detail", command);
    }

    private void doStop(Map<String, Object> ack, String instanceId, String pidFileRel) throws Exception {
        Path pidFile = contained(pidFileRel, "pidFile");
        Optional<Long> alive = alivePid(instanceId, pidFile);
        if (alive.isEmpty()) {
            // 幂等：本就没在跑 = 已停止
            registry.unregister(instanceId);
            Files.deleteIfExists(pidFile);
            ack.put("ok", true);
            ack.put("status", "STOPPED");
            return;
        }
        killTree(alive.get());
        registry.unregister(instanceId);
        Files.deleteIfExists(pidFile);
        log.info("受管进程已停止: instance={} pid={}", instanceId, alive.get());
        ack.put("ok", true);
        ack.put("status", "STOPPED");
    }

    private void doStatus(Map<String, Object> ack, String instanceId, String pidFileRel) throws Exception {
        Path pidFile = contained(pidFileRel, "pidFile");
        Optional<Long> alive = alivePid(instanceId, pidFile);
        ack.put("ok", true);
        if (alive.isPresent()) {
            ack.put("status", "RUNNING");
            ack.put("pid", alive.get());
            ack.put("detail", registry.entryOf(instanceId).map(ProcRegistry.Entry::command).orElse(""));
        } else {
            ack.put("status", "STOPPED");
        }
    }

    // ---------------- internals ----------------

    /** 收容校验：相对路径归一化后必须落在 classify/ 根内（绝对路径/越界一律拒绝） */
    private Path contained(String rel, String what) {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException(what + " 为空（proc 帧路径字段必填）");
        }
        Path p = Path.of(rel);
        if (p.isAbsolute()) {
            throw new IllegalStateException(what + " 必须是相对 classify/ 根的路径，收到绝对路径: " + rel);
        }
        Path resolved = classifyRoot.resolve(p).normalize();
        if (!resolved.startsWith(classifyRoot)) {
            throw new IllegalStateException(what + " 越出收容根 classify/: " + rel);
        }
        return resolved;
    }

    /** 存活 pid 探测：注册表优先，pidfile 兜底（runner 重启前拉起、对账漏网的场景） */
    private Optional<Long> alivePid(String instanceId, Path pidFile) {
        Optional<Long> fromRegistry = registry.entryOf(instanceId)
                .map(ProcRegistry.Entry::pid)
                .filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }
        try {
            if (Files.isRegularFile(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return Optional.of(pid);
                }
            }
        } catch (Exception e) {
            // pidfile 脏/不可读按"没在跑"处理
        }
        return Optional.empty();
    }

    /** 整树强杀：后代先杀（uvicorn worker 等派生进程），根进程最后；等死透上限 KILL_WAIT_MS */
    private void killTree(long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return;
        }
        handle.get().descendants().forEach(ProcessHandle::destroyForcibly);
        handle.get().destroyForcibly();
        try {
            handle.get().onExit().get(KILL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("等待进程死透超时（按已杀继续）: pid={}", pid);
        }
    }
}
