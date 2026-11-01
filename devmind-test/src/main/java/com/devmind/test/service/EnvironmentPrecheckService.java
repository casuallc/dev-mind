package com.devmind.test.service;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.project.EnvironmentService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.PrecheckReport;
import com.devmind.test.dto.PrecheckReport.PrecheckItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-104 FR-06 环境预检：NODE_ONLINE / EXEC_PROTOCOL / HTTP / COMMAND / VERSION_MATCH。
 * 预检在创建运行前执行；失败则运行 BLOCKED，不产生假失败。结果合并写入 environment.precheckJson。
 */
@Service
public class EnvironmentPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentPrecheckService.class);
    private static final int HTTP_TIMEOUT_SEC = 5;

    private final EnvironmentService environmentService;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final AgentNodeStepRunner stepRunner;
    private final ObjectMapper mapper;

    public EnvironmentPrecheckService(EnvironmentService environmentService,
                                      ObjectProvider<AgentNodeConnector> connectorProvider,
                                      AgentNodeStepRunner stepRunner,
                                      ObjectMapper mapper) {
        this.environmentService = environmentService;
        this.connectorProvider = connectorProvider;
        this.stepRunner = stepRunner;
        this.mapper = mapper;
    }

    public PrecheckReport run(String projectId, Long envId, String explicitNodeId) {
        EnvironmentEntity env = environmentService.requireEnvironment(projectId, envId);
        List<String> nodes = new ArrayList<>(environmentService.nodeIdsOf(env));
        if (explicitNodeId != null && !explicitNodeId.isBlank() && !nodes.contains(explicitNodeId.strip())) {
            nodes.add(explicitNodeId.strip());
        }
        return runInternal(projectId, env, nodes, explicitNodeId);
    }

    /** 只预检指定节点本身（方案分布式逐节点判定，避免一个节点失联误伤同环境其他节点）。 */
    public PrecheckReport runForNode(String projectId, Long envId, String nodeId) {
        EnvironmentEntity env = environmentService.requireEnvironment(projectId, envId);
        String node = nodeId == null ? null : nodeId.strip();
        List<String> nodes = node == null || node.isEmpty() ? List.of() : List.of(node);
        return runInternal(projectId, env, nodes, node);
    }

    private PrecheckReport runInternal(String projectId, EnvironmentEntity env,
                                       List<String> nodes, String explicitNodeId) {
        Long envId = env.getId();
        Map<String, Object> config = configOf(env);
        List<PrecheckItem> items = new ArrayList<>();
        AgentNodeConnector connector = connectorProvider.getIfAvailable();

        for (String node : nodes) {
            boolean online = connector != null && connector.isOnline(node);
            items.add(new PrecheckItem("NODE_ONLINE", online, "online", online ? "online" : "offline",
                    online ? "节点在线" : "节点不在线: " + node));
            boolean protocol = online && connector.supports(node, AgentProtocol.EXEC_FRAMES);
            items.add(new PrecheckItem("EXEC_PROTOCOL", protocol,
                    "exec 协议 v" + AgentProtocol.EXEC_FRAMES + "+",
                    protocol ? "supported" : "unsupported",
                    protocol ? "runner 协议满足" : "节点 " + node + " 的 runner 协议版本过低或离线，需升级 runner"));
        }

        Map<String, String> vars = environmentService.variablesOf(env);
        String baseUrl = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
        if (baseUrl != null && !baseUrl.isBlank()) {
            items.add(checkHttp(baseUrl.strip()));
        }

        String command = text(config.get("command"));
        if (command != null && !command.isBlank()) {
            items.add(checkCommand(projectId, nodeFor(nodes, explicitNodeId), command));
        }

        String expectedVersion = text(config.get("expectedVersion"));
        if (expectedVersion != null && !expectedVersion.isBlank()) {
            String actual = env.getProductVersion();
            boolean ok = actual != null && actual.strip().equals(expectedVersion.strip());
            items.add(new PrecheckItem("VERSION_MATCH", ok, expectedVersion.strip(),
                    actual == null ? "(未填写)" : actual,
                    ok ? "版本匹配" : "环境 productVersion 与期望版本不一致"));
        }

        boolean passed = items.stream().allMatch(PrecheckItem::pass);
        Instant now = Instant.now();
        PrecheckReport report = new PrecheckReport(passed, now, items);
        persist(projectId, envId, config, report);
        return report;
    }

    /** 预检配置：兼容 precheckJson 直接是配置对象或 {"config":{…},"last":{…}} 两种形态。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> configOf(EnvironmentEntity env) {
        Map<String, Object> root = readMap(env.getPrecheckJson());
        Object config = root.get("config");
        if (config instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return root;
    }

    /** 清理命令：cleanupJson 支持纯字符串或 {"command":"…"}。 */
    public String cleanupCommandOf(EnvironmentEntity env) {
        if (env.getCleanupJson() == null || env.getCleanupJson().isBlank()) {
            return null;
        }
        String raw = env.getCleanupJson().strip();
        if (!raw.startsWith("{")) {
            return raw;
        }
        return text(readMap(raw).get("command"));
    }

    public CleanupOutcome cleanup(String projectId, EnvironmentEntity env, String nodeId, Consumer<String> sink) {
        String command = cleanupCommandOf(env);
        if (command == null || command.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        Consumer<String> tee = line -> {
            if (out.length() < 4000) {
                out.append(line).append('\n');
            }
            if (sink != null) {
                sink.accept(line);
            }
        };
        var res = stepRunner.runStep(nodeId, projectId, "cleanup-env-" + env.getId(), 1,
                new StepSpec("cleanup", command, null, "test"), Map.of(), null, null, 300L, tee);
        return new CleanupOutcome(res.ok(), res.exitCode(), command, out.toString().strip(),
                res.ok() ? "清理完成" : "清理失败: " + (res.error() == null ? "exit=" + res.exitCode() : res.error()));
    }

    private PrecheckItem checkHttp(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC)).GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            return new PrecheckItem("HTTP", true, "可访问", "HTTP " + status,
                    "目标可访问（HTTP " + status + "）");
        } catch (Exception e) {
            return new PrecheckItem("HTTP", false, "可访问", "不可达", "目标不可达: " + rootMessage(e));
        }
    }

    private PrecheckItem checkCommand(String projectId, String nodeId, String command) {
        if (nodeId == null || nodeId.isBlank()) {
            return new PrecheckItem("COMMAND", false, "exit=0", "无可用节点", "预检命令需要执行节点，但环境未配置节点");
        }
        StringBuilder out = new StringBuilder();
        Consumer<String> sink = line -> {
            if (out.length() < 1000) {
                out.append(line).append('\n');
            }
        };
        var res = stepRunner.runStep(nodeId, projectId, "precheck-" + envKey(nodeId), 1,
                new StepSpec("precheck", command, null, "test"), Map.of(), null, null, 60L, sink);
        String actual = res.ok() ? "exit=0" : "exit=" + res.exitCode();
        return new PrecheckItem("COMMAND", res.ok(), "exit=0", actual,
                res.ok() ? "预检命令通过" : "预检命令失败: " + (res.error() == null ? out.toString().strip() : res.error()));
    }

    private String envKey(String nodeId) {
        return "precheck-" + nodeId;
    }

    private String nodeFor(List<String> nodes, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private void persist(String projectId, Long envId, Map<String, Object> config, PrecheckReport report) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("config", config);
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("passed", report.passed());
            last.put("checkedAt", report.checkedAt().toString());
            last.put("items", report.items());
            root.put("last", last);
            environmentService.savePrecheck(projectId, envId, mapper.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("环境预检结果保存失败: env={} err={}", envId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    public record CleanupOutcome(boolean ok, int exitCode, String command, String output, String message) {
    }
}
