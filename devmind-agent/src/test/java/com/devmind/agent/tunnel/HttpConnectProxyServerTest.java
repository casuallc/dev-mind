package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.common.egress.TunnelFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-70 FR-02/FR-06：HTTP 代理端点端到端——CONNECT 未命中 403、命中但隧道断开 502、
 * 命中在线隧道 → 200 + OPEN 帧参数正确 + 双向 relay；absolute-form 重写 origin-form
 * 并剥 Proxy-* 头经隧道注入。
 */
class HttpConnectProxyServerTest {

    private EgressRuleService ruleService;
    private AgentTunnelRegistry registry;
    private HttpConnectProxyServer server;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        ruleService = mock(EgressRuleService.class);
        EgressProperties props = new EgressProperties();
        props.setHttpPort(0); // ephemeral，避免撞本机常驻 18090
        props.setOpenTimeoutMs(5000);
        props.setHeartbeatIntervalMs(60_000); // 心跳与本测试无关，拉长免干扰
        registry = new AgentTunnelRegistry(ruleService, props, JsonMapper.builder().build());
        server = new HttpConnectProxyServer(props, ruleService, registry);
        when(ruleService.allowedHostsFor(anyLong())).thenReturn(List.of("*.corp.com"));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private int port() throws Exception {
        for (int i = 0; i < 50 && server.boundPort() <= 0; i++) {
            Thread.sleep(100);
        }
        assertTrue(server.boundPort() > 0, "HTTP 代理未就绪");
        return server.boundPort();
    }

    /** 写请求块并读响应状态行（HTTP/1.1 <code> ...） */
    private int exchangeStatusLine(Socket client, String requestBlock) throws Exception {
        OutputStream out = client.getOutputStream();
        out.write(requestBlock.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = client.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            line.write(b);
        }
        String status = line.toString(StandardCharsets.ISO_8859_1).trim();
        return Integer.parseInt(status.split(" ")[1]);
    }

    /** 抓隧道下行二进制帧（同 Socks5ServerTest 姿势） */
    private List<TunnelFrame> tunnelFrames() {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        try {
            verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        List<TunnelFrame> frames = new ArrayList<>();
        for (WebSocketMessage<?> m : captor.getAllValues()) {
            if (m instanceof BinaryMessage bm) {
                java.nio.ByteBuffer buf = bm.getPayload().duplicate();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                frames.add(TunnelFrame.decode(bytes));
            }
        }
        return frames;
    }

    private TunnelFrame awaitFrame(int type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var found = tunnelFrames().stream().filter(f -> f.type() == type).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未等到帧类型 " + type);
    }

    /** 后台假 runner：等到 OPEN 帧后立即回 OPEN_ACK 成功 */
    private Thread ackOnOpen(AtomicReference<TunnelFrame> openRef) {
        Thread acker = new Thread(() -> {
            try {
                while (openRef.get() == null) {
                    var frames = tunnelFrames();
                    clearInvocations(ws);
                    frames.stream().filter(f -> f.type() == TunnelFrame.TYPE_OPEN).findFirst()
                            .ifPresent(open -> {
                                openRef.set(open);
                                registry.onFrame(7L, ws, TunnelFrame.openAck(open.streamId(), null));
                            });
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        acker.setDaemon(true);
        acker.start();
        return acker;
    }

    private EgressRuleEntity corpRule() {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        return rule;
    }

    @Test
    void connectUnmatchedHostRejected403() throws Exception {
        when(ruleService.findRoute(anyString())).thenReturn(Optional.empty());
        try (Socket client = new Socket("127.0.0.1", port())) {
            int code = exchangeStatusLine(client, "CONNECT direct.example.com:443 HTTP/1.1\r\n\r\n");
            assertEquals(403, code); // 白名单语义：未命中不放行
        }
    }

    @Test
    void connectMatchedButTunnelDownRejected502() throws Exception {
        when(ruleService.findRoute("dead.corp.com")).thenReturn(Optional.of(corpRule()));
        registry.onDisconnect(7L, ws);
        try (Socket client = new Socket("127.0.0.1", port())) {
            int code = exchangeStatusLine(client, "CONNECT dead.corp.com:443 HTTP/1.1\r\n\r\n");
            assertEquals(502, code); // 命中但不可用：快速失败不挂起
        }
    }

    @Test
    void connectThroughTunnelOk() throws Exception {
        when(ruleService.findRoute("jira.corp.com")).thenReturn(Optional.of(corpRule()));
        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            int code = exchangeStatusLine(client, "CONNECT jira.corp.com:443 HTTP/1.1\r\n\r\n");
            acker.join(5000);
            assertEquals(200, code);
            TunnelFrame open = openRef.get();
            assertEquals("jira.corp.com", open.host());
            assertEquals(443, open.port());

            // 200 后客户端字节应成 DATA 帧进隧道（relay 已启动）
            clearInvocations(ws);
            client.getOutputStream().write("tls".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            TunnelFrame data = awaitFrame(TunnelFrame.TYPE_DATA, 5000);
            assertEquals("tls", new String(data.payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void absoluteFormRewrittenAndInjected() throws Exception {
        when(ruleService.findRoute("wiki.corp.com")).thenReturn(Optional.of(corpRule()));
        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            // http 明文经代理 = absolute-form：不应有响应行，重写后的请求块直接进隧道
            client.getOutputStream().write(("GET http://wiki.corp.com:8080/a/b?x=1 HTTP/1.1\r\n"
                    + "Host: wiki.corp.com:8080\r\n"
                    + "Proxy-Connection: keep-alive\r\n"
                    + "Accept: */*\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            acker.join(5000);
            TunnelFrame open = openRef.get();
            assertEquals("wiki.corp.com", open.host());
            assertEquals(8080, open.port());

            TunnelFrame data = awaitFrame(TunnelFrame.TYPE_DATA, 5000);
            String rewritten = new String(data.payload(), StandardCharsets.ISO_8859_1);
            assertTrue(rewritten.startsWith("GET /a/b?x=1 HTTP/1.1\r\n"), rewritten);
            assertTrue(rewritten.contains("Host: wiki.corp.com:8080"), rewritten);
            assertTrue(rewritten.contains("Accept: */*"), rewritten);
            assertTrue(!rewritten.contains("Proxy-Connection"), "Proxy-* 头不得转发: " + rewritten);
            assertTrue(rewritten.endsWith("\r\n\r\n"), rewritten);
        }
    }

    @Test
    void malformedRequestRejected400() throws Exception {
        try (Socket client = new Socket("127.0.0.1", port())) {
            int code = exchangeStatusLine(client, "GARBAGE\r\n\r\n");
            assertEquals(400, code);
        }
    }
}
