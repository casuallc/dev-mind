package com.devmind.common.agent.runtime;

import com.devmind.common.agent.SessionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-50：{@link RunnerDeltaAggregator} 的节流与**保序**回归。
 *
 * <p>保序是这层存在的全部理由：runner 上行帧不带 seq，服务端按到达顺序重编，所以正文顺序
 * 只由 sink 的调用顺序决定。用例同时钉死「纯空白增量不能丢」这条容易被当成优化去掉的行为。</p>
 */
class RunnerDeltaAggregatorTest {

    private static final int NEVER_MS = 60_000;

    private final List<SessionEvent> out = new ArrayList<>();

    private RunnerDeltaAggregator aggregator(int flushChars, int flushMs, int maxBufferChars) {
        return new RunnerDeltaAggregator(new RunnerDeltaAggregator.Tuning(flushChars, flushMs, maxBufferChars),
                out::add);
    }

    private SessionEvent delta(String text) {
        return SessionEvent.of(1, "text_delta", text, "stdout");
    }

    private String text(int i) {
        return (String) out.get(i).content();
    }

    // ---------------- 节流 ----------------

    @Test
    void 未到阈值不吐() {
        RunnerDeltaAggregator agg = aggregator(8, NEVER_MS, 1024);
        agg.accept(delta("abc"));
        agg.accept(delta("def"));
        assertTrue(out.isEmpty());
    }

    @Test
    void 攒够字符数吐一条且内容为各片拼接() {
        RunnerDeltaAggregator agg = aggregator(8, NEVER_MS, 1024);
        agg.accept(delta("abc"));
        agg.accept(delta("def"));
        agg.accept(delta("ghi")); // 累计 9 ≥ 8 → 吐出

        assertEquals(1, out.size());
        assertEquals("text_delta", out.getFirst().type());
        assertEquals("abcdefghi", text(0));
        // 沿用缓冲首片的元信息（服务端会重编 seq，但 source 等要留痕）
        assertEquals("stdout", out.getFirst().source());
    }

    @Test
    void 超时到点即吐() {
        RunnerDeltaAggregator agg = aggregator(1000, 0, 1024);
        agg.accept(delta("a"));
        agg.accept(delta("b"));
        assertEquals(2, out.size());
        assertEquals("a", text(0));
        assertEquals("b", text(1));
    }

    @Test
    void 缓冲到顶提前吐且不丢字() {
        // maxBufferChars 是防病态突刺的硬上限：到顶提前吐，而不是截断
        RunnerDeltaAggregator agg = aggregator(1000, NEVER_MS, 5);
        agg.accept(delta("abc"));
        agg.accept(delta("def")); // 累计 6 ≥ 5 → 整片吐出
        assertEquals(1, out.size());
        assertEquals("abcdef", text(0));
    }

    // ---------------- 保序收口 ----------------

    @Test
    void 非增量事件先强制吐出积压正文() {
        RunnerDeltaAggregator agg = aggregator(1000, NEVER_MS, 1024);
        agg.accept(delta("正文片段"));
        agg.accept(SessionEvent.of(2, "assistant", "整段正文", "stdout"));

        assertEquals(2, out.size());
        assertEquals("text_delta", out.get(0).type());
        assertEquals("正文片段", text(0));
        // 全量 assistant 必须排在增量之后——前端靠「全量覆盖流式气泡」收口
        assertEquals("assistant", out.get(1).type());
    }

    @Test
    void 单线程交错时序下正文与事件顺序不乱() {
        RunnerDeltaAggregator agg = aggregator(1000, NEVER_MS, 1024);
        agg.accept(delta("A"));
        agg.accept(SessionEvent.of(2, "tool_use", "Bash", "stdout"));
        agg.accept(delta("B"));
        agg.accept(SessionEvent.of(3, "result", "完成", "stdout"));
        agg.flush();

        assertEquals(List.of("text_delta", "tool_use", "text_delta", "result"),
                out.stream().map(SessionEvent::type).toList());
        assertEquals("A", text(0));
        assertEquals("B", text(2));
    }

    @Test
    void 多线程并发投喂不丢字() throws Exception {
        // 同会话 stdout/stderr 两条读线程共享一个聚合器：锁必须保证不丢片、不抛异常
        RunnerDeltaAggregator agg = aggregator(50, NEVER_MS, 1 << 20);
        int threads = 4;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            String piece = String.valueOf((char) ('a' + t));
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        agg.accept(delta(piece));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        agg.flush();

        int total = out.stream().mapToInt(e -> e.content().length()).sum();
        assertEquals(threads * perThread, total, "并发下的分片一片都不能丢");
        assertTrue(out.stream().allMatch(e -> !e.content().isEmpty()), "不该吐空片");
    }

    @Test
    void 退出时flush吐出尾巴() {
        RunnerDeltaAggregator agg = aggregator(1000, NEVER_MS, 1024);
        agg.accept(delta("最后一段"));
        assertTrue(out.isEmpty());
        agg.flush();
        assertEquals(1, out.size());
        assertEquals("最后一段", text(0));
        // flush 幂等：收口路径可能被重复走到（stdout EOF / kill 各一次）
        agg.flush();
        assertEquals(1, out.size());
    }

    // ---------------- 边界 ----------------

    @Test
    void 空增量不产事件() {
        RunnerDeltaAggregator agg = aggregator(1, NEVER_MS, 1024);
        agg.accept(delta(""));
        agg.accept(SessionEvent.of(2, "text_delta", null, "stdout"));
        agg.flush();
        assertTrue(out.isEmpty());
    }

    @Test
    void 纯空白增量不得丢弃() {
        // token 流里空格常是独立一片；丢了会让拼接结果与全量 assistant 对不上
        RunnerDeltaAggregator agg = aggregator(1000, NEVER_MS, 1024);
        agg.accept(delta("hello"));
        agg.accept(delta(" "));
        agg.accept(delta("world"));
        agg.flush();
        assertEquals("hello world", text(0));
    }

    @Test
    void 两实例互不串味() {
        List<SessionEvent> other = new ArrayList<>();
        RunnerDeltaAggregator a = aggregator(1000, NEVER_MS, 1024);
        RunnerDeltaAggregator b = new RunnerDeltaAggregator(
                RunnerDeltaAggregator.Tuning.defaults(), other::add);

        a.accept(delta("会话A"));
        b.accept(SessionEvent.of(9, "text_delta", "会话B", "stdout"));
        a.flush();
        b.flush();

        assertEquals("会话A", text(0));
        assertEquals("会话B", other.getFirst().content());
        assertEquals(1, out.size());
        assertEquals(1, other.size());
    }
}
