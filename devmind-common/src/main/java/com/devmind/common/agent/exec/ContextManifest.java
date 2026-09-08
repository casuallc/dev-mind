package com.devmind.common.agent.exec;

/**
 * CAP-34 FR-03 上下文包清单：随 launch 帧下发（轻量），runner 据此决定是否 HTTP 拉包
 * 并在拉取后校验完整性（sha256/totalBytes 不符 = launch 失败，不静默降级）。
 *
 * @param entries    注入条目数（知识条目 + skills + docs）
 * @param totalBytes 包 JSON 字节数
 * @param sha256     包 JSON 的 SHA-256（hex 小写）
 */
public record ContextManifest(int entries, long totalBytes, String sha256) {
}
