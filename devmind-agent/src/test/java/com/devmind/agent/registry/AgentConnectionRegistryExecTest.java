package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.exception.DevMindException;
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
 * CAP-56 exec 帧 {@code bundle} 块：必须真的拼进下行帧（本仓库出过三次同类事故——
 * 新字段在 registry 里漏 put，帧看着发出去了、runner 却没拿到，日志里一点线索都没有），
 * 且低版本 runner 要按「必须认识」门控在服务端拦下并指向升级。
 */
class AgentConnectionRegistryExecTest {

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
                mock(AgentConnLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private void helloWithProtocol(Integer version) {
        registry.onHello(node, new AgentHelloMeta("os", "claude", "1.0", null, version, null, null),
                java.util.List.of());
    }

    /** exec 阻塞至 exec_exit：起个线程回帧（同 CollectOutput 测试的 acker 姿势） */
    private AgentExecResult execAndAck(String execId, AgentExecCommand cmd) throws Exception {
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onExecExit("7", execId, 0, false, null);
        });
        acker.start();
        AgentExecResult r = registry.exec("7", cmd, line -> {
        });
        acker.join(10_000);
        return r;
    }

    private static AgentExecCommand cmd(String execId, AgentExecCommand.LabBundleRef bundle) {
        return new AgentExecCommand(execId, "p1", null,
                "\"python\" \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\"",
                null, Map.of("A", "1"), 3600L, null, bundle);
    }

    private String lastFrame() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    void bundleBlockIsSerializedIntoExecFrame() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        AgentExecResult r = execAndAck("e1",
                cmd("e1", new AgentExecCommand.LabBundleRef("evaluation", "42")));
        assertTrue(r.error() == null, String.valueOf(r.error()));

        String payload = lastFrame();
        assertTrue(payload.contains("\"type\":\"exec\""), payload);
        assertTrue(payload.contains("\"bundle\":{"), payload);
        assertTrue(payload.contains("\"kind\":\"evaluation\""), payload);
        assertTrue(payload.contains("\"id\":\"42\""), payload);
        // 已有的字段不能被这次改动挤掉
        assertTrue(payload.contains("\"execId\":\"e1\""), payload);
        assertTrue(payload.contains("\"timeoutSec\":3600"), payload);
        assertTrue(payload.contains("$DEVMIND_LAB_SCRIPT"), payload);
        assertTrue(payload.contains("\"A\":\"1\""), payload);
    }

    @Test
    void noBundleBlockWhenAbsent() throws Exception {
        helloWithProtocol(AgentProtocol.CURRENT);
        execAndAck("e2", cmd("e2", null));
        String payload = lastFrame();
        assertTrue(!payload.contains("bundle"), payload);
    }

    @Test
    void bundleRequiresV14() {
        // 无 hello 版本记录按 v1；报 v13（CAP-51 时代 runner）同样不够
        DevMindException e1 = assertThrows(DevMindException.class, () -> registry.exec("7",
                cmd("e3", new AgentExecCommand.LabBundleRef("evaluation", "1")), line -> {
                }));
        assertTrue(e1.getMessage().contains("升级"), e1.getMessage());

        helloWithProtocol(AgentProtocol.EXEC_BUNDLE - 1);
        DevMindException e2 = assertThrows(DevMindException.class, () -> registry.exec("7",
                cmd("e4", new AgentExecCommand.LabBundleRef("finetune", "1")), line -> {
                }));
        assertTrue(e2.getMessage().contains("v" + AgentProtocol.EXEC_BUNDLE), e2.getMessage());
    }

    @Test
    void plainExecStillAllowedOnOldRunner() throws Exception {
        // 门控只针对带 bundle 的 exec：普通构建/部署步骤在 v13 节点上照旧可跑
        helloWithProtocol(AgentProtocol.EXEC_BUNDLE - 1);
        AgentExecResult r = execAndAck("e5", cmd("e5", null));
        assertEquals(0, r.exitCode());
        assertTrue(lastFrame().contains("\"type\":\"exec\""));
    }
}
