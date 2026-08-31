package com.devmind.serveradapter.spi;

import java.util.Map;

/**
 * 已解密配置的服务器连接目标（CAP-07 FR-01）：由 ServerOperationService 从 ProjectServerEntity
 * 加载并解密 accessConfig 后交给适配器。
 *
 * @param config SSH: {host, port, username, authType, password|privateKey, ...}
 *               HTTP: {baseUrl, token, timeoutMs, ...}
 */
public record ServerTarget(
        Long id,
        String projectId,
        String name,
        String env,
        String accessType,
        Map<String, Object> config) {

    public String str(String key) {
        Object v = config == null ? null : config.get(key);
        return v == null ? null : v.toString();
    }

    public String str(String key, String def) {
        String v = str(key);
        return v == null || v.isBlank() ? def : v;
    }

    public int intVal(String key, int def) {
        String v = str(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
