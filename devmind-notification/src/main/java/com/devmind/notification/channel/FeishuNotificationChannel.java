package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 飞书自定义机器人 Webhook 通道（CAP-75 FR-01，CAP-06 FR-03 外部推送）：
 * POST webhookUrl，msg_type=text。
 * 配置（code=feishu 的 configJson）：{"webhookUrl":"https://open.feishu.cn/open-apis/bot/v2/hook/...","secret":""}
 * secret 非空时按飞书加签规范附带 timestamp/sign：HmacSHA256(key=timestamp+"\n"+secret, data="") → Base64。
 */
@Component
public class FeishuNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(FeishuNotificationChannel.class);

    private final ObjectMapper mapper;
    private final HttpClient http;

    public FeishuNotificationChannel(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    public String name() {
        return "飞书自定义机器人";
    }

    @Override
    public void send(NotificationChannelEntity channel, NotificationView notification) {
        Map<String, Object> cfg = Configs.parse(mapper, channel.getConfigJson());
        Object url = cfg.get("webhookUrl");
        if (url == null || url.toString().isBlank()) {
            throw new IllegalStateException("飞书 Webhook 未配置 webhookUrl");
        }
        String content = String.format("【Dev-Mind · %s】%s%n%s",
                notification.level(), notification.title(),
                notification.body() == null ? "" : notification.body());
        try {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("text", content);
            Map<String, Object> payload = new LinkedHashMap<>();
            Object secret = cfg.get("secret");
            if (secret != null && !secret.toString().isBlank()) {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                payload.put("timestamp", timestamp);
                payload.put("sign", sign(timestamp, secret.toString()));
            }
            payload.put("msg_type", "text");
            payload.put("content", text);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("飞书推送失败: code={} body={}", resp.statusCode(), resp.body());
                throw new IllegalStateException("飞书 HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书推送被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("飞书推送网络失败: " + e.getMessage(), e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("飞书 payload 序列化失败", e);
        }
    }

    /** 飞书自定义机器人加签：HmacSHA256(key=timestamp+"\n"+secret, data="") → Base64。 */
    static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("飞书加签失败", e);
        }
    }
}
