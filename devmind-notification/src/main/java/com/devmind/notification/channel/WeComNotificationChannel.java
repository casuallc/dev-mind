package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 企业微信 Webhook 通道（FR-03 外部推送）：
 * POST webhookUrl，msgtype=text。
 * 配置（code=wecom 的 configJson）：{"webhookUrl":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."}
 */
@Component
public class WeComNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WeComNotificationChannel.class);

    private final ObjectMapper mapper;
    private final HttpClient http;

    public WeComNotificationChannel(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String code() {
        return "wecom";
    }

    @Override
    public String name() {
        return "企业微信 Webhook";
    }

    @Override
    public void send(NotificationChannelEntity channel, NotificationView notification) {
        Map<String, Object> cfg = Configs.parse(mapper, channel.getConfigJson());
        Object url = cfg.get("webhookUrl");
        if (url == null || url.toString().isBlank()) {
            throw new IllegalStateException("企微 Webhook 未配置 webhookUrl");
        }
        String content = String.format("【Dev-Mind · %s】%s%n%s",
                notification.level(), notification.title(),
                notification.body() == null ? "" : notification.body());
        try {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", content);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "text");
            payload.put("text", text);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("企微推送失败: code={} body={}", resp.statusCode(), resp.body());
                throw new IllegalStateException("企微 HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("企微推送被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("企微推送网络失败: " + e.getMessage(), e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("企微 payload 序列化失败", e);
        }
    }
}
