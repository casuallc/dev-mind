package com.devmind.agent.runner;

import com.devmind.common.agent.runtime.CliEventParser;
import com.devmind.common.agent.runtime.CliProcessLauncher;
import com.devmind.common.agent.runtime.FakeProcessLauncher;
import com.devmind.common.agent.runtime.RuntimeSettings;
import com.devmind.common.agent.runtime.SessionExecutor;
import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.exec.RunnerWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CAP-21 agent runner 入口（瘦 jar，无 Spring）：
 * 反向 WS 连服务端 → 收 launch/input/authorize/finish/kill/suspend 指令 → 本地拉起
 * claude 子进程（CLI 接触点 {@link CliProcessLauncher}/{@link CliEventParser} 已上移
 * devmind-common 的 agent.runtime 包，CAP-30），解析后的事件流回传。
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
        // CAP-30：内核参数从 Spring 配置类换成 RuntimeSettings 值对象（runner 无 Spring）
        RuntimeSettings settings = RuntimeSettings.defaults()
                .withClaudePath(config.claudePath())
                .withPermissionMode(config.permissionMode());
        // executor=claude（默认）/ fake（内置假进程，自测/无 claude 环境）；
        // protocol（user message / permission_result 拼装）两种 executor 同 schema，恒用 CliProcessLauncher 构造器
        CliProcessLauncher protocol = new CliProcessLauncher(settings, mapper);
        SessionExecutor executor = "fake".equalsIgnoreCase(config.executor())
                ? new FakeProcessLauncher()
                : protocol;
        CliEventParser parser = new CliEventParser(mapper, settings);

        ServerConnection[] connRef = new ServerConnection[1];
        RunnerSessionRegistry sessions = new RunnerSessionRegistry(parser, frame -> connRef[0].send(frame));
        RunnerWorkspace workspace = new RunnerWorkspace(config.workspaceRoot());

        // CAP-34 FR-04：连接前先现场对账——强杀/崩溃残留的孤儿 claude 进程整树回收，
        // 无主目录登记（超龄删除归 FR-05 GC）。对账完再上线，hello 的 activeSessions 才是真实清单
        var report = new com.devmind.common.agent.exec.WorkspaceReconciler(config.workspaceRoot()).reconcile();
        if (!report.ownerlessDirs().isEmpty()) {
            log.info("对账登记无主会话目录 {} 个（待 GC）: {}", report.ownerlessDirs().size(), report.ownerlessDirs());
        }

        // CAP-34 FR-05：工作区磁盘占用（hello/heartbeat 上报）+ 超龄会话目录 GC 调度
        var gc = new com.devmind.common.agent.exec.WorkspaceGc(config.workspaceRoot());
        java.util.concurrent.atomic.AtomicLong workspaceBytes = new java.util.concurrent.atomic.AtomicLong(-1);
        Thread.ofVirtual().name("workspace-usage-init").start(() -> workspaceBytes.set(gc.usageBytes()));

        ServerConnection conn = new ServerConnection(config, mapper,
                frame -> handleFrame(frame, config, configFile, protocol, executor, sessions, workspace, connRef[0]),
                () -> connRef[0].send(helloFrame(sessions, version, workspaceBytes.get())));
        connRef[0] = conn;

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(
                () -> conn.send(heartbeatFrame(workspaceBytes.get())), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        // FR-05 GC：启动 10 分钟后首跑，其后按 gcIntervalMinutes 巡检；跑完刷新磁盘占用缓存
        ScheduledExecutorService gcTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "workspace-gc");
            t.setDaemon(true);
            return t;
        });
        gcTimer.scheduleWithFixedDelay(() -> {
            try {
                gc.run(config.gcDays(), java.util.Set.copyOf(sessions.activeSessionIds()));
                workspaceBytes.set(gc.usageBytes());
            } catch (Exception e) {
                log.warn("工作区 GC 异常（下轮重试）: {}", e.getMessage());
            }
        }, 10, config.gcIntervalMinutes(), TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            log.info("runner 关闭中，终止全部会话进程");
            heartbeat.shutdownNow();
            gcTimer.shutdownNow();
            sessions.killAll();
            conn.shutdown();
        }));

        conn.run(); // 阻塞：断线重连循环
    }

    private static Map<String, Object> helloFrame(RunnerSessionRegistry sessions, String version, long workspaceBytes) {
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("os", System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        hello.put("capabilities", "claude");
        hello.put("version", version);
        hello.put("activeSessions", sessions.activeSessionIds());
        if (workspaceBytes >= 0) {
            hello.put("workspaceBytes", workspaceBytes); // FR-05：占用未算完（-1）时不带，旧服务端本就不读
        }
        return hello;
    }

    /** FR-05：心跳带 workspaceBytes 让节点页数据保鲜（重连才发 hello 更新太慢）。 */
    private static Map<String, Object> heartbeatFrame(long workspaceBytes) {
        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("type", "heartbeat");
        if (workspaceBytes >= 0) {
            hb.put("workspaceBytes", workspaceBytes);
        }
        return hb;
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
                    protocol.buildUserMessage(frame.path("text").asText(""), parseImages(frame)));
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
     * CAP-32：input 帧 images 字段（base64 图片）→ InputImage。attachmentId 远端不可知，
     * 占位空串（组帧只用 mediaType/data）；旧版服务端不带该字段时为空列表，行为不变。
     */
    private static List<InputImage> parseImages(JsonNode frame) {
        JsonNode images = frame.path("images");
        if (!images.isArray() || images.isEmpty()) {
            return List.of();
        }
        List<InputImage> out = new ArrayList<>();
        for (JsonNode img : images) {
            String mediaType = img.path("mediaType").asText("");
            String data = img.path("data").asText("");
            if (!mediaType.isBlank() && !data.isBlank()) {
                out.add(new InputImage("", null, mediaType, data));
            }
        }
        return out;
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

            // CAP-30：kind="chat" = 通用问答——沙箱 <workspaceRoot>/_chat/<sid>（幂等创建，
            // resume 复用），进程退出 finalizer 递归删除；无 clone/push 语义。
            // CAP-25：launch 帧带 repo 块 → runner 托管工作区（clone/fetch/会话 worktree，
            // 结束 push+清理）；无 repo 块 → 旧行为（project.<id> 映射/兜底目录，代码节点自理）。
            // token 只进 RunnerWorkspace.RepoCtx（内存），严禁日志输出。
            Path workDir;
            Path sessionDir = null; // CAP-34 FR-04：pid 文件落点（legacy 映射路径为 null，不参与重启对账）
            RunnerSessionRegistry.SessionFinalizer finalizer = null;
            String kind = frame.path("kind").asText("");
            JsonNode repoNode = frame.path("repo");
            JsonNode reposNode = frame.path("repos");
            if ("chat".equals(kind)) {
                workDir = workspace.prepareChat(sessionId);
                sessionDir = workDir;
                finalizer = sid -> workspace.cleanChat(sid, msg -> sessions.reportSystem(sid, msg));
                log.info("问答沙箱就绪: session={} cwd={}", sessionId, workDir);
            } else if (reposNode.isArray() && reposNode.size() > 1) {
                // CAP-31 多库会话：repos 数组 >1 → 聚合目录模式（cwd=聚合根，各库子目录 <name>/）
                if (projectId == null || projectId.isBlank()) {
                    throw new IllegalStateException("带 repos 块的 launch 必须携带 projectId");
                }
                java.util.List<RunnerWorkspace.RepoSpec> specs = new java.util.ArrayList<>();
                for (JsonNode rn : reposNode) {
                    specs.add(new RunnerWorkspace.RepoSpec(
                            rn.path("remoteUrl").asText(""),
                            rn.path("baseBranch").asText(""),
                            rn.path("branch").asText(""),
                            rn.path("token").asText(""),
                            rn.path("name").asText("")));
                }
                RunnerWorkspace.MultiCtx mctx = workspace.prepareMulti(sessionId, projectId, specs);
                workDir = mctx.aggRoot();
                sessionDir = mctx.aggRoot();
                finalizer = sid -> workspace.finishMulti(mctx, msg -> sessions.reportSystem(sid, msg));
                log.info("多库托管工作区就绪: session={} repos={} cwd={}", sessionId, specs.size(), workDir);
            } else if (repoNode.isObject() && !repoNode.path("remoteUrl").asText("").isBlank()) {
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
                sessionDir = ctx.sessionDir();
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

            // CAP-34 FR-03：launch 帧带 contextManifest → HTTP 拉包物化后再拉起；
            // 拉取/校验/物化失败即 launch 失败（走 catch 回 launched{ok:false}），不静默降级为无上下文会话
            JsonNode manifestNode = frame.path("contextManifest");
            if (manifestNode.isObject() && !manifestNode.path("sha256").asText("").isBlank()) {
                ContextPuller.pullAndMaterialize(config, sessionId, manifestNode, workDir);
            }

            // CAP-24：服务端下发的提交身份等附加 env（旧服务端无此字段 → 空）
            java.util.Map<String, String> env = new java.util.HashMap<>();
            JsonNode envNode = frame.path("env");
            if (envNode.isObject()) {
                envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
            }
            Process proc = executor.launch(new SessionExecutor.LaunchContext(
                    sessionId, workDir, taskSpec, model, permissionMode, env));
            sessions.register(sessionId, proc, finalizer, sessionDir);
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
