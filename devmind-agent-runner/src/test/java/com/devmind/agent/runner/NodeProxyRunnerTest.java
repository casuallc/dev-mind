package com.devmind.agent.runner;

import com.devmind.common.agent.exec.NodeProxy;
import com.devmind.common.agent.exec.RunnerWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-43 runner 侧节点代理：帧 proxy 对象 → NodeProxy holder 刷新（refreshProxy）；
 * exec scope 命中时脚本进程注入 HTTP(S)_PROXY 四件（帧 env 同名键不覆盖）。
 */
class NodeProxyRunnerTest {

    @TempDir
    Path tmp;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @AfterEach
    void clearHolder() {
        NodeProxy.clear(); // holder 是进程级静态，用例间必须复位
    }

    @Test
    void refreshProxySetsReplacesAndClearsHolder() {
        // 无 proxy 字段：不动 holder
        AgentRunnerMain.refreshProxy(MAPPER.readTree("{\"type\":\"launch\"}"));
        assertNull(NodeProxy.get());

        // 携带 proxy：set
        AgentRunnerMain.refreshProxy(MAPPER.readTree(
                "{\"proxy\":{\"url\":\"http://127.0.0.1:8443\",\"scopes\":[\"git\",\"exec\"]}}"));
        assertEquals("http://127.0.0.1:8443", NodeProxy.get().url());
        assertTrue(NodeProxy.appliesTo("git"));
        assertTrue(NodeProxy.appliesTo("exec"));
        assertFalse(NodeProxy.appliesTo("claude"));

        // 后续帧无 proxy 字段：保持上一帧的值（不抖动）
        AgentRunnerMain.refreshProxy(MAPPER.readTree("{\"type\":\"exec\"}"));
        assertEquals("http://127.0.0.1:8443", NodeProxy.get().url());

        // 替换 scopes
        AgentRunnerMain.refreshProxy(MAPPER.readTree(
                "{\"proxy\":{\"url\":\"http://127.0.0.1:8443\",\"scopes\":[\"claude\"]}}"));
        assertFalse(NodeProxy.appliesTo("git"));
        assertTrue(NodeProxy.appliesTo("claude"));

        // 空 url = 服务端显式清空（节点代理被清除）
        AgentRunnerMain.refreshProxy(MAPPER.readTree("{\"proxy\":{\"url\":\"\",\"scopes\":[]}}"));
        assertNull(NodeProxy.get());
    }

    /** 跑一个 exec（env | grep -i _proxy），收集 exec_log 输出与 exit code。 */
    private record ExecOutcome(int exit, String log) {
    }

    private ExecOutcome runEnvExec(Map<String, String> frameEnv) throws Exception {
        RunnerConfig cfg = new RunnerConfig("ws://localhost", "t", "", "acceptEdits",
                tmp, Map.of(), 2, "fake", tmp.resolve("ws"), 14, 360, 10, List.of(),
                List.of("env", "grep"), "bash", 24);
        List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        ExecHandler handler = new ExecHandler(cfg, new RunnerWorkspace(tmp.resolve("ws")), frames::add);

        var frame = MAPPER.createObjectNode();
        frame.put("type", "exec")
                .put("execId", "e-proxy")
                .put("command", "env | grep -i _proxy")
                .put("timeoutSec", 60);
        frame.set("env", MAPPER.valueToTree(frameEnv));
        handler.handle(frame);

        // handle 投虚拟线程异步执行：轮询等 exec_exit（真实进程，给足余量）
        long deadline = System.currentTimeMillis() + 60_000;
        Integer exit = null;
        while (System.currentTimeMillis() < deadline) {
            exit = frames.stream()
                    .filter(f -> "exec_exit".equals(f.get("type")))
                    .map(f -> ((Number) f.get("code")).intValue())
                    .findFirst().orElse(null);
            if (exit != null) {
                break;
            }
            Thread.sleep(100);
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> f : frames) {
            if ("exec_log".equals(f.get("type"))) {
                sb.append(f.get("chunk"));
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(exit, "exec_exit 未到，帧=" + frames);
        return new ExecOutcome(exit, sb.toString());
    }

    @Test
    void execScopeInjectsProxyEnvWithoutOverridingFrameEnv() throws Exception {
        // 未配置代理：注入缺席（环境本就没有这些变量时 grep 无输出 → exit 1）
        ExecOutcome plain = runEnvExec(Map.of());
        assertFalse(plain.log().contains("127.0.0.1:8443"), plain.log());

        // 命中 exec scope → 注入（大小写四件；Windows 环境表大小写不敏感可能合并，按值断言）
        NodeProxy.set("http://127.0.0.1:8443", java.util.Set.of("exec"));
        ExecOutcome proxied = runEnvExec(Map.of());
        assertEquals(0, proxied.exit(), proxied.log());
        assertTrue(proxied.log().contains("=http://127.0.0.1:8443"), proxied.log());

        // 帧 env 已有同名键 → 该键帧 env 优先（其余大小写变体仍注入，符合「同名键不覆盖」语义）
        ExecOutcome overridden = runEnvExec(Map.of("HTTP_PROXY", "http://frame-env:1"));
        assertTrue(overridden.log().contains("HTTP_PROXY=http://frame-env:1"), overridden.log());
        assertFalse(overridden.log().contains("HTTP_PROXY=http://127.0.0.1:8443"), overridden.log());

        // scope 不含 exec → 不注入
        NodeProxy.set("http://127.0.0.1:8443", java.util.Set.of("git", "claude"));
        ExecOutcome otherScope = runEnvExec(Map.of());
        assertFalse(otherScope.log().contains("127.0.0.1:8443"), otherScope.log());
    }
}
