package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ServerConnection.Backoff}：认证拒绝（1008）不能被握手成功复位成 1s 高频重试
 * （本机残留 runner 1s 一次刷了 16 万次的根因）。
 */
class ServerConnectionBackoffTest {

    @Test
    void authRejectedEscalatesAndCapsAt5min() {
        ServerConnection.Backoff b = new ServerConnection.Backoff();
        // 反复「握手成功 + 立即 1008 关闭」：不健康，退避必须持续累进
        assertEquals(30_000, b.next(true, false));
        assertEquals(60_000, b.next(true, false));
        assertEquals(120_000, b.next(true, false));
        assertEquals(240_000, b.next(true, false));
        assertEquals(300_000, b.next(true, false));
        assertEquals(300_000, b.next(true, false)); // 封顶
    }

    @Test
    void normalDisconnectEscalatesAndCapsAt30s() {
        ServerConnection.Backoff b = new ServerConnection.Backoff();
        assertEquals(1000, b.next(false, false));
        assertEquals(2000, b.next(false, false));
        assertEquals(4000, b.next(false, false));
        for (int i = 0; i < 10; i++) {
            b.next(false, false);
        }
        assertEquals(30_000, b.next(false, false)); // 封顶
    }

    @Test
    void healthyConnectionResetsBoth() {
        ServerConnection.Backoff b = new ServerConnection.Backoff();
        b.next(true, false); // 累进认证退避
        b.next(true, false);
        assertEquals(1000, b.next(false, true)); // 健康连接复位，普通断线回到 1s
        assertEquals(30_000, b.next(true, false)); // 认证拒绝也从头开始
    }

    @Test
    void quickNonAuthCloseKeepsNormalBackoffEscalating() {
        // 被踢的旧连接（1000 立即关闭）不算健康，不复位退避
        ServerConnection.Backoff b = new ServerConnection.Backoff();
        assertEquals(1000, b.next(false, false));
        assertEquals(2000, b.next(false, false));
        assertEquals(4000, b.next(false, false));
    }
}
