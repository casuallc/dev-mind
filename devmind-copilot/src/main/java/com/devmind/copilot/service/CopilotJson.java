package com.devmind.copilot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-76 FR-28 存储分层：执行快照不掩码；审计副本按敏感键掩码并截断。
 */
public final class CopilotJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(token|secret|password|passwd|api[_-]?key|authorization|cookie|credential|private[_-]?key).*");
    private static final Pattern SECRET_TEXT = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{8,}|Bearer\\s+[A-Za-z0-9._~+/=-]{8,})");

    private CopilotJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = MAPPER.readValue(json, Map.class);
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 审计副本：敏感键 → ***，长字符串截断。 */
    @SuppressWarnings("unchecked")
    public static String maskedArgsJson(Map<String, Object> args, int maxChars) {
        Object masked = mask(args, maxChars, 0);
        return write(masked);
    }

    private static Object mask(Object value, int maxChars, int depth) {
        if (depth > 6) {
            return "…";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, SECRET_KEY.matcher(key).matches() ? "***" : mask(v, maxChars, depth + 1));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(mask(item, maxChars, depth + 1));
            }
            return out;
        }
        if (value instanceof String s) {
            return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
        }
        return value;
    }

    /** 自由文本脱敏（FR-28）：`sk-…` / `Bearer …` 一律替换。 */
    public static String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return SECRET_TEXT.matcher(text).replaceAll("***");
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
