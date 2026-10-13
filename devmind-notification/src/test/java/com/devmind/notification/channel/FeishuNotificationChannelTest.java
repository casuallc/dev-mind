package com.devmind.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;
import com.devmind.notification.model.NotificationLevel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** CAP-75 FR-01：飞书自定义机器人通道 payload/加签/空配置降级。 */
class FeishuNotificationChannelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private NotificationView notification() {
        return new NotificationView(1L, NotificationLevel.P0, "WAITING_INPUT", "会话等待输入",
                "正文内容", "SESSION", "s-1", null, List.of(), Map.of(), null, Instant.now());
    }

    private NotificationChannelEntity channel(String configJson) {
        NotificationChannelEntity e = new NotificationChannelEntity();
        e.setCode("feishu");
        e.setName("飞书自定义机器人");
        e.setEnabled(true);
        e.setLevelThreshold("P0");
        e.setConfigJson(configJson);
        return e;
    }

    @Test
    void sendsTextPayload() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        new FeishuNotificationChannel(mapper).send(
                channel("{\"webhookUrl\":\"" + url + "\"}"), notification());

        Map<String, Object> payload = mapper.readValue(lastBody.get(), Map.class);
        assertEquals("text", payload.get("msg_type"));
        Map<String, Object> content = (Map<String, Object>) payload.get("content");
        assertTrue(content.get("text").toString().contains("【Dev-Mind · P0】会话等待输入"));
        assertTrue(content.get("text").toString().contains("正文内容"));
        assertTrue(payload.get("sign") == null, "无 secret 不应带 sign");
    }

    @Test
    void signsWhenSecretPresent() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        new FeishuNotificationChannel(mapper).send(
                channel("{\"webhookUrl\":\"" + url + "\",\"secret\":\"sec-1\"}"), notification());

        Map<String, Object> payload = mapper.readValue(lastBody.get(), Map.class);
        String timestamp = (String) payload.get("timestamp");
        String sign = (String) payload.get("sign");
        assertNotNull(timestamp);
        assertNotNull(sign);
        // 独立复算：HmacSHA256(key=timestamp+"\n"+secret, data="") → Base64
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((timestamp + "\n" + "sec-1").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        assertEquals(expected, sign);
    }

    @Test
    void blankWebhookUrlFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new FeishuNotificationChannel(mapper).send(
                        channel("{\"webhookUrl\":\"\"}"), notification()));
        assertTrue(ex.getMessage().contains("webhookUrl"));
    }
}
