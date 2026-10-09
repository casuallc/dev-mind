package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.InputFile;
import com.devmind.common.agent.InputImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-68 input 帧 files 字段组帧：files[{id,name,mediaType,data}] 必须完整下发
 * （防「字段静默丢失」同类事故——新字段拼帧必须有断言钉住）；可选字段不门控，
 * 空 files/images 时不带对应字段（老 runner 行为不变）。
 */
class AgentConnectionRegistryInputTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, new AgentProperties(),
                MAPPER, listenerProvider, mock(ObjectProvider.class), mock(AgentConnLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null,
                com.devmind.common.agent.AgentProtocol.CURRENT, null, null), List.of());
    }

    private JsonNode lastFrame() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return MAPPER.readTree(captor.getValue().getPayload());
    }

    @Test
    void filesFieldCarriesIdNameMediaTypeAndData() throws Exception {
        registry.sendInput("7", "s1", "看下这个文档", List.of(),
                List.of(new InputFile("att-1", "需求说明书.pdf", "application/pdf", "QUJD"),
                        new InputFile("att-2", "设计稿.png", "image/png", "QUJD")));

        JsonNode frame = lastFrame();
        assertEquals("input", frame.path("type").asText());
        assertEquals("s1", frame.path("sessionId").asText());
        assertEquals("看下这个文档", frame.path("text").asText());
        JsonNode files = frame.path("files");
        assertTrue(files.isArray() && files.size() == 2, "files 数组必须完整下发");
        assertEquals("att-1", files.get(0).path("id").asText());
        assertEquals("需求说明书.pdf", files.get(0).path("name").asText());
        assertEquals("application/pdf", files.get(0).path("mediaType").asText());
        assertEquals("QUJD", files.get(0).path("data").asText());
        assertEquals("设计稿.png", files.get(1).path("name").asText());
        // 未带图片时不带 images 字段
        assertFalse(frame.has("images"));
    }

    @Test
    void imagesAndFilesCoexist() throws Exception {
        registry.sendInput("7", "s1", "图和文件都看下",
                List.of(new InputImage("att-img", "截图.png", "image/png", "QUJD")),
                List.of(new InputFile("att-doc", "说明.md", "text/markdown", "QUJD")));

        JsonNode frame = lastFrame();
        assertTrue(frame.path("images").isArray() && frame.path("images").size() == 1);
        assertEquals("image/png", frame.path("images").get(0).path("mediaType").asText());
        assertTrue(frame.path("files").isArray() && frame.path("files").size() == 1);
        assertEquals("att-doc", frame.path("files").get(0).path("id").asText());
    }

    @Test
    void emptyAttachmentsOmitBothFields() throws Exception {
        registry.sendInput("7", "s1", "纯文本");

        JsonNode frame = lastFrame();
        assertFalse(frame.has("images"));
        assertFalse(frame.has("files"));
        assertEquals("纯文本", frame.path("text").asText());
    }
}
