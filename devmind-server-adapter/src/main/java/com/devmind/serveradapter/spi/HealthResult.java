package com.devmind.serveradapter.spi;

/** 健康检查结果（CAP-07 FR-02）。 */
public record HealthResult(boolean ok, String message, long durationMs) {
}
