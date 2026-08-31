package com.devmind.notification.channel;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通道配置 JSON 解析小工具。 */
final class Configs {

    private Configs() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m != null ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }
}
