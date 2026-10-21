package com.devmind.task.engine;

import com.devmind.task.model.AsyncTaskEntity;

/**
 * CAP-80 FR-04 重试退避计算（纯函数，单测钉死）：
 * FIXED=固定 retryIntervalMs；EXPONENTIAL=间隔 ×2^(已失败次数-1) 递增、封顶 30 分钟。
 */
public final class RetryPolicy {

    /** EXPONENTIAL 封顶 30 分钟 */
    public static final long EXPONENTIAL_CAP_MS = 30 * 60_000L;

    private RetryPolicy() {
    }

    /**
     * @param backoff       FIXED / EXPONENTIAL（null 按 FIXED）
     * @param intervalMs    基础间隔（retry_interval_ms）
     * @param failedAttempt 刚失败的 attempt 序号（1 起）
     */
    public static long nextDelayMs(String backoff, long intervalMs, int failedAttempt) {
        if (!AsyncTaskEntity.BACKOFF_EXPONENTIAL.equals(backoff)) {
            return intervalMs;
        }
        int shift = Math.max(0, failedAttempt - 1);
        if (shift >= 20) {
            return EXPONENTIAL_CAP_MS;
        }
        long delay = intervalMs << shift;
        return delay < 0 || delay > EXPONENTIAL_CAP_MS ? EXPONENTIAL_CAP_MS : delay;
    }
}
