package com.devmind.task.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-80 FR-04 退避计算：FIXED 恒定、EXPONENTIAL ×2 递增、封顶 30 分钟、位移溢出安全。
 */
class RetryPolicyTest {

    @Test
    void fixedReturnsIntervalRegardlessOfAttempt() {
        assertEquals(5_000L, RetryPolicy.nextDelayMs("FIXED", 5_000L, 1));
        assertEquals(5_000L, RetryPolicy.nextDelayMs("FIXED", 5_000L, 9));
        // null 按 FIXED
        assertEquals(5_000L, RetryPolicy.nextDelayMs(null, 5_000L, 3));
    }

    @Test
    void exponentialDoublesPerFailedAttempt() {
        assertEquals(10_000L, RetryPolicy.nextDelayMs("EXPONENTIAL", 10_000L, 1));
        assertEquals(20_000L, RetryPolicy.nextDelayMs("EXPONENTIAL", 10_000L, 2));
        assertEquals(40_000L, RetryPolicy.nextDelayMs("EXPONENTIAL", 10_000L, 3));
        assertEquals(160_000L, RetryPolicy.nextDelayMs("EXPONENTIAL", 10_000L, 5));
    }

    @Test
    void exponentialCapsAtThirtyMinutes() {
        assertEquals(RetryPolicy.EXPONENTIAL_CAP_MS, RetryPolicy.nextDelayMs("EXPONENTIAL", 60_000L, 6));
        assertEquals(RetryPolicy.EXPONENTIAL_CAP_MS, RetryPolicy.nextDelayMs("EXPONENTIAL", 60_000L, 30));
    }

    @Test
    void hugeShiftNeverOverflowsToNegative() {
        // 左移溢出曾会把延迟算成负数（立即重试风暴），shift>=20 直接封顶
        assertEquals(RetryPolicy.EXPONENTIAL_CAP_MS, RetryPolicy.nextDelayMs("EXPONENTIAL", Long.MAX_VALUE / 2, 25));
    }
}
