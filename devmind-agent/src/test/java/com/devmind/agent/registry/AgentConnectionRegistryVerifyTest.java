package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.AgentVerifyCommand;
import com.devmind.common.agent.AgentVerifyResult;
import com.devmind.common.exception.DevMindException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-73 verify_push/verify_pack/verify_patch 帧：全部字段必须真的拼进下行帧（本仓库
 * launch/exec 帧四次同类事故——新字段漏 put，帧发出去了 runner 却拿不到），低版本
 * runner 按「必须认识」门控在服务端拦下并指向升级（VERIFY_RUNNER_UPGRADE_REQUIRED）。
 */
class AgentConnectionRegistryVerifyTest {

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;
    private AgentNodeEntity node;

    @BeforeEach
    void setUp() {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, new AgentProperties(),
                JsonMapper.builder().build(), listenerProvider, mock(ObjectProvider.class),
                mock(AgentAuditLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private void helloWithProtocol(Integer version) {
        registry.onHello(node, new AgentHelloMeta("os", "claude", "1.0", null, version, null, null),
                List.of());
    }

    /** verify 阻塞至 verify_ack：起个线程回帧（同 exec 测试的 acker 姿势） */
    private void ackLater(String requestId) {
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onVerifyAck("7", requestId, true, null, null, null);
        });
        acker.start();
    }

    private String lastFrame() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    void pushFrameCarriesAllFields() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        ackLater("vp1");
        AgentVerifyResult r = registry.verifyPush("7", new AgentVerifyCommand.Push(
                "vp1", 42L, "p1", "alice", "req-abcd1234",
                "https://gitlab.example.com/g/r.git", "feature/req-abcd1234", "tok-1", 300L),
                line -> {
                });
        assertTrue(r.ok(), String.valueOf(r.error()));

        String payload = lastFrame();
        assertTrue(payload.contains("\"type\":\"verify_push\""), payload);
        assertTrue(payload.contains("\"requestId\":\"vp1\""), payload);
        assertTrue(payload.contains("\"runId\":42"), payload);
        assertTrue(payload.contains("\"projectId\":\"p1\""), payload);
        assertTrue(payload.contains("\"workspaceOwner\":\"alice\""), payload);
        assertTrue(payload.contains("\"workspaceKey\":\"req-abcd1234\""), payload);
        assertTrue(payload.contains("\"remoteUrl\":\"https://gitlab.example.com/g/r.git\""), payload);
        assertTrue(payload.contains("\"branch\":\"feature/req-abcd1234\""), payload);
        assertTrue(payload.contains("\"token\":\"tok-1\""), payload);
        assertTrue(payload.contains("\"timeoutSec\":300"), payload);
    }

    @Test
    void packFrameCarriesAllFields() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        ackLater("vk1");
        AgentVerifyResult r = registry.verifyPack("7", new AgentVerifyCommand.Pack(
                "vk1", 42L, "p1", "verify-req-abcd1234",
                "https://gitlab.example.com/g/r.git", "feature/req-abcd1234", "deadbeef", "tok-2",
                List.of(new AgentVerifyCommand.Pack.Step("构建", "mvn -q install", "server"),
                        new AgentVerifyCommand.Pack.Step("打包", "make dist", null)),
                "build/output", Map.of("VERIFY_MODULES", "server"), 1800L, 512L * 1024 * 1024),
                line -> {
                });
        assertTrue(r.ok(), String.valueOf(r.error()));

        String payload = lastFrame();
        assertTrue(payload.contains("\"type\":\"verify_pack\""), payload);
        assertTrue(payload.contains("\"requestId\":\"vk1\""), payload);
        assertTrue(payload.contains("\"runId\":42"), payload);
        assertTrue(payload.contains("\"workspaceKey\":\"verify-req-abcd1234\""), payload);
        assertTrue(payload.contains("\"commitSha\":\"deadbeef\""), payload);
        assertTrue(payload.contains("\"token\":\"tok-2\""), payload);
        assertTrue(payload.contains("\"name\":\"构建\""), payload);
        assertTrue(payload.contains("\"command\":\"mvn -q install\""), payload);
        assertTrue(payload.contains("\"workingDir\":\"server\""), payload);
        assertTrue(payload.contains("\"name\":\"打包\""), payload);
        assertTrue(payload.contains("\"outputDir\":\"build/output\""), payload);
        assertTrue(payload.contains("\"VERIFY_MODULES\":\"server\""), payload);
        assertTrue(payload.contains("\"timeoutSec\":1800"), payload);
        assertTrue(payload.contains("\"bundleMaxBytes\":536870912"), payload);
    }

    @Test
    void patchFrameCarriesAllFields() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        ackLater("vt1");
        AgentVerifyResult r = registry.verifyPatch("7", new AgentVerifyCommand.Patch(
                "vt1", 42L, "/opt/app/lib", "systemctl stop app", "systemctl start app",
                "9-reqabcd1234", 600L), line -> {
        });
        assertTrue(r.ok(), String.valueOf(r.error()));

        String payload = lastFrame();
        assertTrue(payload.contains("\"type\":\"verify_patch\""), payload);
        assertTrue(payload.contains("\"requestId\":\"vt1\""), payload);
        assertTrue(payload.contains("\"runId\":42"), payload);
        assertTrue(payload.contains("\"patchDir\":\"/opt/app/lib\""), payload);
        assertTrue(payload.contains("\"preHook\":\"systemctl stop app\""), payload);
        assertTrue(payload.contains("\"postHook\":\"systemctl start app\""), payload);
        assertTrue(payload.contains("\"manifestKey\":\"9-reqabcd1234\""), payload);
        assertTrue(payload.contains("\"timeoutSec\":600"), payload);
    }

    @Test
    void verifyFramesRequireV22() {
        // 无 hello 版本记录按 v1
        assertThrows(DevMindException.class, () -> registry.verifyPush("7",
                new AgentVerifyCommand.Push("vp9", 1L, "p1", "alice", "req-x",
                        "https://g.example.com/r.git", "b", "t", 60L), line -> {
                }));
        // v21（CAP-70 时代 runner）同样不够
        helloWithProtocol(AgentProtocol.VERIFY_FRAMES - 1);
        DevMindException e = assertThrows(DevMindException.class, () -> registry.verifyPatch("7",
                new AgentVerifyCommand.Patch("vt9", 1L, "/opt/app", null, null, "k", 60L),
                line -> {
                }));
        assertTrue(e.getMessage().contains("VERIFY_RUNNER_UPGRADE_REQUIRED"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingVerify() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        Thread starter = new Thread(() -> registry.verifyPush("7",
                new AgentVerifyCommand.Push("vpd", 1L, "p1", "alice", "req-x",
                        "https://g.example.com/r.git", "b", "t", 60L), line -> {
                }));
        // 直接调私有骨架不可行，走公开方法但拿不到结果——改用 future 语义：
        // 先占位再断连，ack 等待者应以「节点断连」收口
        starter.start();
        Thread.sleep(100);
        registry.onDisconnect(node, ws);
        starter.join(10_000);
        // 无异常即通过：done future 被断连清理逻辑完成（failed("节点断连…")），
        // 若漏清理 starter 会卡满 60s+120s 超时，join 超时即失败
        assertTrue(!starter.isAlive(), "断连后 verify 等待者未收口");
    }

    @Test
    void ackResultFieldsReachCaller() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onVerifyAck("7", "vkr", true, null, 17, null);
        });
        acker.start();
        AgentVerifyResult r = registry.verifyPack("7", new AgentVerifyCommand.Pack(
                "vkr", 1L, "p1", "verify-req-x", "https://g.example.com/r.git", "b", "c", "t",
                List.of(new AgentVerifyCommand.Pack.Step("s", "echo hi", null)),
                "out", Map.of(), 60L, 1024L), line -> {
        });
        acker.join(10_000);
        assertTrue(r.ok());
        assertEquals(17, r.fileCount());
    }
}
