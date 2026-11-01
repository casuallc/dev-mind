package com.devmind.test.service;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.project.EnvironmentService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.PrecheckReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-104 环境预检回归：节点/协议/HTTP/命令/版本五类检查与配置兼容形态。 */
class EnvironmentPrecheckTest {

    private EnvironmentService environmentService;
    private AgentNodeConnector connector;
    private AgentNodeStepRunner stepRunner;
    private EnvironmentPrecheckService service;
    private EnvironmentEntity env;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        environmentService = mock(EnvironmentService.class);
        connector = mock(AgentNodeConnector.class);
        stepRunner = mock(AgentNodeStepRunner.class);
        ObjectProvider<AgentNodeConnector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connector);
        service = new EnvironmentPrecheckService(environmentService, provider, stepRunner, new ObjectMapper());
        env = new EnvironmentEntity();
        env.setId(3L);
        when(environmentService.requireEnvironment("p1", 3L)).thenReturn(env);
    }

    @Test
    void configAcceptsWrappedAndDirectShapes() {
        env.setPrecheckJson("{\"config\":{\"command\":\"echo hi\"},\"last\":{\"passed\":false}}");
        assertEquals(Map.of("command", "echo hi"), service.configOf(env));

        env.setPrecheckJson("{\"command\":\"echo hi\",\"expectedVersion\":\"1.0\"}");
        assertEquals("echo hi", service.configOf(env).get("command"));
        assertEquals("1.0", service.configOf(env).get("expectedVersion"));

        env.setPrecheckJson(null);
        assertTrue(service.configOf(env).isEmpty());
    }

    @Test
    void cleanupCommandAcceptsPlainStringAndObject() {
        env.setCleanupJson("rm -rf tmp/report");
        assertEquals("rm -rf tmp/report", service.cleanupCommandOf(env));

        env.setCleanupJson("{\"command\":\"make clean\"}");
        assertEquals("make clean", service.cleanupCommandOf(env));

        env.setCleanupJson(" ");
        assertNull(service.cleanupCommandOf(env));
    }

    @Test
    void offlineNodeAndVersionMismatchBlockRun() {
        env.setPrecheckJson("{\"command\":\"echo hi\",\"expectedVersion\":\"2.0\"}");
        env.setProductVersion("1.0");
        when(environmentService.nodeIdsOf(env)).thenReturn(List.of("node-1"));
        when(environmentService.variablesOf(env)).thenReturn(Map.of());
        when(connector.isOnline("node-1")).thenReturn(false);
        when(stepRunner.runStep(anyString(), eq("p1"), anyString(), anyInt(), any(StepSpec.class),
                anyMap(), any(), any(), anyLong(), any())).thenReturn(new StepResult(false, 7, "boom"));

        PrecheckReport report = service.run("p1", 3L, null);

        assertFalse(report.passed());
        Map<String, PrecheckReport.PrecheckItem> items = new java.util.LinkedHashMap<>();
        report.items().forEach(i -> items.put(i.name(), i));
        assertFalse(items.get("NODE_ONLINE").pass());
        assertFalse(items.get("EXEC_PROTOCOL").pass());
        assertFalse(items.get("COMMAND").pass());
        assertEquals("exit=7", items.get("COMMAND").actual());
        assertTrue(items.get("COMMAND").message().contains("boom"));
        assertFalse(items.get("VERSION_MATCH").pass());
        assertTrue(items.get("VERSION_MATCH").message().contains("不一致"));
        verify(environmentService).savePrecheck(eq("p1"), eq(3L),
                org.mockito.ArgumentMatchers.contains("\"last\""));
    }

    @Test
    void allChecksPassWithOnlineNodeAndReachableHttp() throws IOException {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
                .create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            env.setPrecheckJson("{\"command\":\"echo hi\",\"expectedVersion\":\"1.0\"}");
            env.setProductVersion("1.0");
            when(environmentService.nodeIdsOf(env)).thenReturn(List.of("node-1"));
            when(environmentService.variablesOf(env)).thenReturn(
                    Map.of("baseUrl", "http://localhost:" + server.getAddress().getPort() + "/health"));
            when(connector.isOnline("node-1")).thenReturn(true);
            when(connector.supports(eq("node-1"), anyInt())).thenReturn(true);
            when(stepRunner.runStep(anyString(), eq("p1"), anyString(), anyInt(), any(StepSpec.class),
                    anyMap(), any(), any(), anyLong(), any())).thenReturn(new StepResult(true, 0, null));

            PrecheckReport report = service.run("p1", 3L, null);

            assertTrue(report.passed(), report.items().toString());
            assertTrue(report.items().stream().anyMatch(i -> "HTTP".equals(i.name()) && i.pass()));
            assertTrue(report.items().stream().allMatch(PrecheckReport.PrecheckItem::pass));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void commandWithoutAnyNodeFailsExplicitly() {
        env.setPrecheckJson("{\"command\":\"echo hi\"}");
        when(environmentService.nodeIdsOf(env)).thenReturn(List.of());
        when(environmentService.variablesOf(env)).thenReturn(Map.of());

        PrecheckReport report = service.run("p1", 3L, null);

        assertFalse(report.passed());
        PrecheckReport.PrecheckItem command = report.items().stream()
                .filter(i -> "COMMAND".equals(i.name())).findFirst().orElseThrow();
        assertFalse(command.pass());
        assertTrue(command.message().contains("需要执行节点"));
    }
}
