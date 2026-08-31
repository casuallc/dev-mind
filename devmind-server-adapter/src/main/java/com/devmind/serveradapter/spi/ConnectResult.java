package com.devmind.serveradapter.spi;

/** 连通性测试结果（CAP-07 FR-02）。 */
public record ConnectResult(boolean ok, String message, long durationMs) {
}
