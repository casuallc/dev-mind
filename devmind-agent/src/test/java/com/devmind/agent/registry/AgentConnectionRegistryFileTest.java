package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentFileRequest;
import com.devmind.common.agent.AgentFileResult;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-65 file 帧链路：组帧形状（op/root/path/roots 全字段钉死，防静默丢失事故）→
 * file_ack 完成等待 → 白名单空/越界拒绝（服务端第一道）→ 大小预检 → 协议 v18 门控 →
 * 断连批量失败。
 */
class AgentConnectionRegistryFileTest {

    private AgentNodeService nodeService;
    private AgentConnectionRegistry registry;
    private WebSocketSession ws;
    private AgentNodeEntity node;

    @BeforeEach
    void setUp() {
        nodeService = mock(AgentNodeService.class);
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
        node.setFileRoots("[\"D:/data\",\"/var/log\"]");
        when(nodeService.require(7L)).thenReturn(node);
        registry.onConnect(node, ws);
        // v18 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.3.0", null, 18, null, null),
                List.of());
    }

    private record Call(AtomicReference<AgentFileResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startFile(AgentFileRequest req) throws Exception {
        AtomicReference<AgentFileResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.file("7", req));
            } catch (Throwable e) {
                error.set(e);
            }
        });
        // 清掉此前帧的调用记录：否则 timeout 校验会被「上一次已经发过帧」直接满足
        org.mockito.Mockito.clearInvocations(ws);
        t.start();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        return new Call(result, error, t, captor.getValue().getPayload());
    }

    private static String requestIdOf(String payload) {
        String marker = "\"requestId\":\"";
        int i = payload.indexOf(marker);
        return payload.substring(i + marker.length(), payload.indexOf('"', i + marker.length()));
    }

    @Test
    void frameShapeAndAckRouting() throws Exception {
        Call call = startFile(AgentFileRequest.write("D:/data", "a/b.txt", "中文内容"));
        // 红线：组帧字段一个不能少（静默丢失已三次事故）；roots 全量随帧下发（DB 权威）
        assertTrue(call.payload().contains("\"type\":\"file\""), call.payload());
        assertTrue(call.payload().contains("\"op\":\"write\""), call.payload());
        assertTrue(call.payload().contains("\"root\":\"D:/data\""), call.payload());
        assertTrue(call.payload().contains("\"path\":\"a/b.txt\""), call.payload());
        assertTrue(call.payload().contains("\"content\":\"中文内容\""), call.payload());
        assertTrue(call.payload().contains("\"roots\":[\"D:/data\",\"/var/log\"]"), call.payload());
        assertTrue(requestIdOf(call.payload()).startsWith("fa-"), call.payload());

        registry.onFileAck("7", requestIdOf(call.payload()), true, Map.of("size", 12), null);
        call.thread().join(10_000);
        AgentFileResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals(12, r.payload().get("size"));
    }

    @Test
    void uploadFrameCarriesTransferFields() throws Exception {
        Call call = startFile(AgentFileRequest.upload("/var/log", "pkg.bin", "tr-1", 1024,
                "a".repeat(64)));
        assertTrue(call.payload().contains("\"op\":\"upload\""), call.payload());
        assertTrue(call.payload().contains("\"transferId\":\"tr-1\""), call.payload());
        assertTrue(call.payload().contains("\"size\":1024"), call.payload());
        assertTrue(call.payload().contains("\"sha256\":\"" + "a".repeat(64) + "\""), call.payload());
        registry.onFileAck("7", requestIdOf(call.payload()), true, Map.of(), null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        Call call = startFile(AgentFileRequest.read("D:/data", "big.bin"));
        registry.onFileAck("7", requestIdOf(call.payload()), false, null,
                "二进制文件不支持在线预览，请下载查看");
        call.thread().join(10_000);
        AgentFileResult r = call.result().get();
        assertTrue(r != null && !r.ok());
        assertEquals("二进制文件不支持在线预览，请下载查看", r.error());
    }

    @Test
    void unknownRequestIdIgnored() {
        // 迟到的 ack（等待已超时/断连清理后）不炸不攒
        registry.onFileAck("7", "fa-nonexistent", true, Map.of(), null);
    }

    @Test
    void emptyRootsRejectedBeforeFraming() throws Exception {
        node.setFileRoots(null);
        var e = assertThrows(DevMindException.class,
                () -> registry.file("7", AgentFileRequest.list("D:/data", "")));
        assertTrue(e.getMessage().contains("白名单"), e.getMessage());
        verify(ws, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void rootOutsideWhitelistRejected() {
        // 归一化后精确匹配：盘符大小写不敏感命中，目录前缀不等于 root
        assertTrue(com.devmind.agent.service.AgentFileRoots.containsRoot(
                List.of("D:/data"), "d:\\data\\"), "盘符大小写与尾分隔符归一化后应命中");
        var e = assertThrows(DevMindException.class,
                () -> registry.file("7", AgentFileRequest.list("D:/data/sub", "")));
        assertTrue(e.getMessage().contains("白名单"), e.getMessage());
    }

    @Test
    void oversizeWriteRejectedBeforeFraming() throws Exception {
        String big = "x".repeat(513 * 1024);
        var e = assertThrows(DevMindException.class,
                () -> registry.file("7", AgentFileRequest.write("D:/data", "a.txt", big)));
        assertTrue(e.getMessage().contains("512KB"), e.getMessage());
        verify(ws, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // v17 runner 不认识 file 帧 → 409 门控，不静默下发挂到超时
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 17, null, null),
                List.of());
        var e = assertThrows(DevMindException.class,
                () -> registry.file("7", AgentFileRequest.list("D:/data", "")));
        assertTrue(e.getMessage().contains("v18"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingFiles() throws Exception {
        Call call = startFile(AgentFileRequest.list("D:/data", ""));
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        // 断连清理完成一个失败结果（不抛异常）：REST 层把 error 透传为 409 文案
        AgentFileResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && !r.error().isBlank());
    }
}
