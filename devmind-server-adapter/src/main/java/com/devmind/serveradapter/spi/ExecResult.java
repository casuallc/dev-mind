package com.devmind.serveradapter.spi;

/** 远程执行结果（CAP-07 FR-02）。 */
public record ExecResult(int exitCode, boolean success, String stdout, String stderr, long durationMs) {
}
