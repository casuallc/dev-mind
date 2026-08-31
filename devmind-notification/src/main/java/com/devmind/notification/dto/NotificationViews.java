package com.devmind.notification.dto;

import com.devmind.notification.model.NotificationEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * NotificationEntity → NotificationView 的静态映射工具。
 * 被 NotificationService 与 NotificationWsHandler 共用（避免 handler 反向依赖 service）。
 */
public final class NotificationViews {

    private static final TypeReference<List<ActionDef>> ACTIONS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STATUS = new TypeReference<>() {
    };

    private NotificationViews() {
    }

    public static NotificationView toView(NotificationEntity e, ObjectMapper mapper) {
        return new NotificationView(
                e.getId(),
                e.getLevel(),
                e.getEventType(),
                e.getTitle(),
                e.getBody(),
                e.getEntityType(),
                e.getEntityId(),
                parseActions(e.getActions(), mapper),
                parseStatus(e.getChannelStatus(), mapper),
                e.getReadAt(),
                e.getCreatedAt());
    }

    private static List<ActionDef> parseActions(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ActionDef> list = mapper.readValue(json, ACTIONS);
            return list != null ? list : List.of();
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private static Map<String, String> parseStatus(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> map = mapper.readValue(json, STATUS);
            return map != null ? map : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    /** 字符串 JSON → Map（通道配置等）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonMap(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m != null ? m : new LinkedHashMap<>();
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Map → 字符串 JSON（写入实体列）。 */
    public static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    public static <T> List<T> listFrom(ObjectMapper mapper, String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<T> list = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, type));
            return list != null ? list : new ArrayList<>();
        } catch (JacksonException e) {
            return new ArrayList<>();
        }
    }
}
