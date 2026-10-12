package com.devmind.usage.dto;

import java.time.Instant;

/**
 * 请求日志行（逐回合账本视图）。title 为服务端截断的会话 taskSpec / 问答标题；
 * tokensPerSec = output×1000/durationMs（durationMs 空或 0 → null）。costUsd 照常住账
 * 随 DTO 返回，但前端不展示（订阅/中继场景成本失真）。
 */
public record UsageRecordRow(Long id, String source, String sessionId, String title,
                             String requirementId, String projectId, String model, String owner,
                             Instant createdAt, Long durationMs, Double costUsd,
                             Long inputTokens, Long outputTokens,
                             Long cacheReadTokens, Long cacheCreationTokens, Double tokensPerSec) {
}
