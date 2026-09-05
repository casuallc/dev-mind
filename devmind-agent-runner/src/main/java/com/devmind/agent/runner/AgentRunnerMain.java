package com.devmind.agent.runner;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.runtime.CliEventParser;
import com.devmind.session.runtime.CliProcessLauncher;
import com.devmind.session.runtime.FakeProcessLauncher;
import com.devmind.session.runtime.SessionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CAP-21 agent runner 入口（瘦 jar，无 Spring）：
 * 反向 WS 连服务端 → 收 launch/input/authorize/finish/kill/suspend 指令 → 本地拉起
 * claude 子进程（复用 devmind-session 的 {@link CliProcessLauncher}/{@link CliEventParser}），
 * 解析后的事件流回传。
 *
 * <p>用法：{@code java -jar devmind-agent-runner.jar [agent.properties 路径]}（默认 ./agent.properties）。</p>
 */
public class AgentRunnerMain {

    private static final Logger log = LoggerFactory.getLogger(AgentRunnerMain.class);
    /** 心跳周期：须明显小于服务端 heartbeatTimeoutMs（默认 45s） */
    private static final long HEARTBEAT_MS = 15_000;

    public static void main(String[] args) throws Exception {
        Path configFile = args.length > 0 ? Path.of(args[0]) : Path.of("agent.properties");
        RunnerConfig config = RunnerConfig.load(configFile);
        String version = resolveVersion();
        log.info("devmind-agent-runner {} 启动，配置: {}", version, configFile.toAbsolutePath());

        ObjectMapper mapper = JsonMapper.builder().build();
        SessionProperties sessionProps = new SessionProperties();
        sessionProps.setClaudePath(config.claudePath());
        sessionProps.setPermissionMode(config.permissionMode());
        // executor=claude（默认）/ fake（内置假进程，自测/无 claude 环境）；
        // protocol（user message / permission_result 拼装）两种 executor 同 schema，恒用 CliProcessLauncher 构造器
        CliProcessLauncher protocol = new CliProcessLauncher(sessionProps, mapper);
        SessionExecutor executor = "fake".equalsIgnoreCase(config.executor())
                ? new FakeProcessLauncher()
                : protocol;
        CliEventParser parser = new CliEventParser(mapper, sessionProps);

        ServerConnection[] connRef = new ServerConnection[1];
        RunnerSessionRegistry sessions = new RunnerSessionRegistry(parser, frame -> connRef[0].send(frame));
        RunnerWorkspace workspace = new RunnerWorkspace(config.workspaceRoot());

        ServerConnection conn = new ServerConnection(config, mapper,
                frame -> handleFrame(frame, config, configFile, protocol, executor, sessions, workspace, connRef[0]),
                () -> connRef[0].send(helloFrame(sessions, version)));
        connRef[0] = conn;

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(
                () -> conn.send(Map.of("type", "heartbeat")), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            log.info("runner 关闭中，终止全部会话进程");
            heartbeat.shutdownNow();
            sessions.killAll();
            conn.shutdown();
        }));

        conn.run(); // 阻塞：断线重连循环
    }

    private static Map<String, Object> helloFrame(RunnerSessionRegistry sessions, String version) {
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("os", System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        hello.put("capabilities", "claude");
        hello.put("version", version);
        hello.put("activeSessions", sessions.activeSessionIds());
        return hello;
    }

    private static void handleFrame(JsonNode frame, RunnerConfig config, Path configFile,
                                    CliProcessLauncher protocol, SessionExecutor executor,
                                    RunnerSessionRegistry sessions, RunnerWorkspace workspace,
                                    ServerConnection conn) {
        String type = frame.path("type").asText("");
        String sessionId = frame.path("sessionId").asText("");
        switch (type) {
            case "launch" -> handleLaunch(frame, sessionId, config, executor, sessions, workspace, conn);
            case "input" -> sessions.writeStdin(sessionId,
                    protocol.buildUserMessage(frame.path("text").asText("")));
            case "authorize" -> sessions.writeStdin(sessionId, protocol.buildPermissionResult(
                    frame.path("requestId").asText("unknown"),
                    frame.path("accepted").asBoolean(false),
                    frame.path("scope").asText("once")));
            case "finish" -> sessions.closeStdin(sessionId);
            case "kill", "suspend" -> sessions.kill(sessionId);
            case "upgrade" -> handleUpgrade(frame, config, configFile, sessions, conn);
            default -> log.debug("未知指令类型: {}", type);
        }
    }

    /**
     * FR-09 手动升级：有活跃会话回 busy 推迟（不杀会话）；否则同步下载+校验（本方法跑在
     * WS listener 线程，心跳在独立调度线程不受影响）→ ack 落线 → spawn SelfUpdater → 退出，
     * 换包与重启由 SelfUpdater 在本进程退出后完成。
     */
    private static void handleUpgrade(JsonNode frame, RunnerConfig config, Path configFile,
                                      RunnerSessionRegistry sessions, ServerConnection conn) {
        int active = sessions.size();
        if (active > 0) {
            log.info("有 {} 个活跃会话，推迟升级", active);
            conn.send(Map.of("type", "upgrade_ack", "ok", false,
                    "reason", "busy", "activeSessions", active));
            return;
        }
        Path target;
        Path newJar;
        try {
            target = RunnerUpgrader.currentJar()
                    .orElseThrow(() -> new IllegalStateException("非 jar 启动，无法自升级"));
            newJar = target.resolveSibling(target.getFileName() + ".new");
            log.info("开始下载升级包: version={}", frame.path("version").asText(""));
            RunnerUpgrader.downloadAndVerify(RunnerUpgrader.downloadUrl(config),
                    frame.path("sha256").asText(""), newJar);
        } catch (Exception e) {
            log.warn("升级包下载/校验失败: {}", e.getMessage());
            conn.send(Map.of("type", "upgrade_ack", "ok", false,
                    "reason", String.valueOf(e.getMessage())));
            return;
        }
        log.info("升级包就绪: {}，ack 后退出换包", newJar);
        conn.sendAndWait(Map.of("type", "upgrade_ack", "ok", true), 5000);
        try {
            RunnerUpgrader.spawnSelfUpdater(target, newJar, configFile);
        } catch (Exception e) {
            log.error("SelfUpdater 拉起失败: {}", e.getMessage(), e);
            // 仍退出：心跳停后服务端判 OFFLINE，保留 .new 现场人工恢复
        }
        System.exit(0); // shutdown hook: killAll（空）+ conn.shutdown()
    }

    private static void handleLaunch(JsonNode frame, String sessionId, RunnerConfig config,
                                     SessionExecutor executor, RunnerSessionRegistry sessions,
                                     RunnerWorkspace workspace, ServerConnection conn) {
        try {
            if (sessions.size() >= config.maxConcurrent()) {
                throw new IllegalStateException("runner 并发会话已达上限 " + config.maxConcurrent());
            }
            String projectId = frame.path("projectId").asText(null);
            String taskSpec = frame.path("taskSpec").asText("");
            String model = frame.path("model").asText("");
            String permissionMode = frame.path("permissionMode").asText("");

            // CAP-25：launch 帧带 repo 块 → runner 托管工作区（clone/fetch/会话 worktree，
            // 结束 push+清理）；无 repo 块 → 旧行为（project.<id> 映射/兜底目录，代码节点自理）。
            // token 只进 RunnerWorkspace.RepoCtx（内存），严禁日志输出。
            Path workDir;
            RunnerSessionRegistry.SessionFinalizer finalizer = null;
            JsonNode repoNode = frame.path("repo");
            if (repoNode.isObject() && !repoNode.path("remoteUrl").asText("").isBlank()) {
                if (projectId == null || projectId.isBlank()) {
                    throw new IllegalStateException("带 repo 块的 launch 必须携带 projectId");
                }
                RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                        repoNode.path("remoteUrl").asText(""),
                        repoNode.path("baseBranch").asText(""),
                        repoNode.path("branch").asText(""),
                        repoNode.path("token").asText(""));
                RunnerWorkspace.RepoCtx ctx = workspace.prepare(sessionId, projectId, spec);
                workDir = ctx.sessionDir();
                finalizer = sid -> workspace.finish(ctx, msg -> sessions.reportSystem(sid, msg));
                log.info("托管工作区就绪: session={} cwd={}", sessionId, workDir);
            } else {
                workDir = config.resolveWorkDir(projectId);
                // Windows CreateProcess error=267：cwd 不存在直接拉起失败。
                // 兜底 workDir 属临时目录，缺则自建；项目映射目录缺失必须报错（自建会悄悄跑错目录）
                if (!Files.isDirectory(workDir)) {
                    if (projectId != null && config.projectPaths().containsKey(projectId)) {
                        throw new IllegalStateException("项目映射目录不存在: " + workDir
                                + "（agent.properties 的 project." + projectId + " 指向无效路径）");
                    }
                    Files.createDirectories(workDir);
                    log.info("兜底工作目录不存在已创建: {}", workDir);
                }
            }
            log.info("拉起会话: session={} project={} cwd={}", sessionId, projectId, workDir);

            // CAP-24：服务端下发的提交身份等附加 env（旧服务端无此字段 → 空）
            java.util.Map<String, String> env = new java.util.HashMap<>();
            JsonNode envNode = frame.path("env");
            if (envNode.isObject()) {
                envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
            }
            Process proc = executor.launch(new SessionExecutor.LaunchContext(
                    sessionId, workDir, taskSpec, model, permissionMode, env));
            sessions.register(sessionId, proc, finalizer);
            conn.send(Map.of("type", "launched", "sessionId", sessionId, "ok", true));
        } catch (Exception e) {
            log.warn("拉起会话失败: session={} err={}", sessionId, e.getMessage());
            conn.send(Map.of("type", "launched", "sessionId", sessionId,
                    "ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    private static String resolveVersion() {
        try (var in = AgentRunnerMain.class.getResourceAsStream("/runner-version.txt")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            }
        } catch (Exception e) {
            // 忽略
        }
        return "dev";
    }
}
