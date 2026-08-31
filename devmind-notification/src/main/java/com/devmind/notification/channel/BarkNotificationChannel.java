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
 * Bark 通道（FR-03 外部推送，手机端接收）：
 * POST {server}/{key}，payload 带 title/body/level/group。
 * 配置（notification_channels.code=bark 的 configJson）：{"server":"https://api.day.app","key":"你的key"}
 */
@Component
public class BarkNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(BarkNotificationChannel.class);

    private final ObjectMapper mapper;
    private final HttpClient http;

    public BarkNotificationChannel(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String code() {
        return "bark";
    }

    @Override
    public String name() {
        return "Bark（iPhone 推送）";
    }

    @Override
    public void send(NotificationChannelEntity channel, NotificationView notification) {
        Map<String, Object> cfg = Configs.parse(mapper, channel.getConfigJson());
        Object key = cfg.get("key");
        if (key == null || key.toString().isBlank()) {
            throw new IllegalStateException("Bark 未配置 key");
        }
        String server = cfg.getOrDefault("server", "https://api.day.app").toString();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", notification.title());
            payload.put("body", notification.body() == null ? "" : notification.body());
            payload.put("group", "Dev-Mind");
            payload.put("level", switch (notification.level()) {
                case P0 -> "critical";
                case P1 -> "active";
                case P2 -> "passive";
            });
            payload.put("device_key", key.toString());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(server + "/" + key + "?group=Dev-Mind"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("Bark 推送失败: code={} body={}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Bark HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bark 推送被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Bark 推送网络失败: " + e.getMessage(), e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Bark payload 序列化失败", e);
        }
    }
}
