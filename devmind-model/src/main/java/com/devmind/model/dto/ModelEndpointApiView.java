package com.devmind.model.dto;

import java.time.Instant;

/**
 * CAP-48 FR-01 端点 HTTP 视图。<b>永不含凭据</b>——只有 {@code hasApiKey} 标识是否已配置
 * （与 CAP-18 {@code IntegrationView.hasToken} 同口径）。
 *
 * @param dimensions        向量维度（连接测试探测写入，null = 尚未探测）
 * @param lastTestOk        最近一次连接测试结果（null = 从未测试）
 * @param lastTestMessage   最近一次测试的诊断信息（已脱敏）
 */
public record ModelEndpointApiView(
        Long id,
        String kind,
        String name,
        String provider,
        String baseUrl,
        String model,
        boolean hasApiKey,
        Integer dimensions,
        int timeoutSeconds,
        int batchSize,
        Integer topK,
        Double threshold,
        String status,
        boolean isDefault,
        Instant lastTestAt,
        Boolean lastTestOk,
        String lastTestMessage,
        Instant createdAt,
        Instant updatedAt) {
}
