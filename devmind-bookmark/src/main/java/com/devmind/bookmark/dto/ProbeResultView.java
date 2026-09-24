package com.devmind.bookmark.dto;

import java.time.Instant;

/**
 * FR-04 探测结果（同步单条返回；批量探测落库后由列表轮询读到同一形态）。
 *
 * @param statusCode 成功时为 HTTP 状态码；失败时为异常同义描述（TIMEOUT/DNS/CONNECT/SSL/IO/ERROR/BAD_URL/PRIVATE）
 */
public record ProbeResultView(
        String id,
        String status,
        String statusCode,
        Long latencyMs,
        Instant checkedAt) {
}
