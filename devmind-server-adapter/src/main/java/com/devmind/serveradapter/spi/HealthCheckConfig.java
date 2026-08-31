package com.devmind.serveradapter.spi;

/**
 * 健康检查配置（CAP-07 FR-02）。
 *
 * @param type           http | command
 * @param url            type=http：目标 URL
 * @param expectedStatus type=http：期望状态码（默认 200）
 * @param command        type=command：执行的检查命令
 */
public record HealthCheckConfig(String type, String url, Integer expectedStatus, String command) {

    public static HealthCheckConfig http(String url, Integer expectedStatus) {
        return new HealthCheckConfig("http", url, expectedStatus, null);
    }

    public static HealthCheckConfig command(String command) {
        return new HealthCheckConfig("command", null, null, command);
    }
}
