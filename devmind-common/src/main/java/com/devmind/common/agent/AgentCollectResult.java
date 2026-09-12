package com.devmind.common.agent;

/**
 * CAP-39 产出按需回传 ack（collect_output 帧的应答）：runner 扫 `.devmind/output/`
 * 同步上传完成后才回 ack，故服务端收到 ok=true 时 `session_outputs` 已落库，回读无竞态。
 *
 * @param ok    上传成功（无产出目录/空目录也算成功）；false = 失败或会话不在本节点运行
 * @param error 失败原因（ok=true 时为 null）
 */
public record AgentCollectResult(boolean ok, String error) {
}
