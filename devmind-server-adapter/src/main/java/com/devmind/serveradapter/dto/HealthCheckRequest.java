package com.devmind.serveradapter.dto;

/**
 * 健康检查请求（CAP-07 FR-02）：type=http 用 url+expectedStatus；type=command 用 command。
 */
public record HealthCheckRequest(
        String type,
        String url,
        Integer expectedStatus,
        String command) {
}
