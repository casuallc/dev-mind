package com.devmind.common.agent.runtime;

import com.devmind.common.agent.SessionEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-50：CLI 会话的正文增量聚合器（**每会话一个实例**，线程安全）。
 *
 * <p>claude 开 {@code --include-partial-messages} 后逐 token 吐 {@code text_delta}，若原样上行，
 * WS 帧率、环形缓冲占位与 {@code session_events} 行数都会放大两个数量级。本类按「攒够 N 字符
 * 或过了 M 毫秒」吐一条，口径与 CAP-49 模型执行体一致（见 {@link Tuning#defaults()}）。</p>
 *
 * <p><b>顺序不变量</b>（本类存在的全部理由）：runner 上行帧不带 seq，服务端按到达顺序重编
 * （{@code RemoteSessionRuntime.ingest}），所以正文顺序**只**由 sink 的调用顺序决定。因此本类
 * 把「flush 的 send」与「判定用的锁」绑在同一把锁内：任何非 delta 事件到达时，先在锁内把积压
 * 正文交出去，再交出该事件。同会话的 stdout / stderr 两条读线程因此不会把正文插错位置——
 * 若在锁内判定、锁外发送，另一条线程就会挤进两者之间。</p>
 *
 * <p>增量里的纯空白**不能丢**：token 流里一个空格常常就是独立一片，丢了会让拼接结果与全量
 * {@code assistant} 对不上（前端以全量覆盖收口，但会话被中断时增量是唯一痕迹）。只丢真正为空的片。</p>
 */
public class RunnerDeltaAggregator {

    /**
     * @param flushChars     攒够多少字符即吐
     * @param flushMs        距上次吐出多少毫秒即吐
     * @param maxBufferChars 缓冲硬上限，防病态突刺把字符串堆爆（到顶提前吐，不丢字）
     */
    public record Tuning(int flushChars, int flushMs, int maxBufferChars) {

        /** 与 CAP-49 同一口径（ChatProperties.streamFlushChars / streamFlushMs = 24 / 120）。 */
        public static Tuning defaults() {
            return new Tuning(24, 120, 100 * 1024);
        }
    }

    private final Tuning tuning;
    private final Consumer<SessionEvent> sink;
    /** 既是缓冲也是锁对象：flushLocked 的判定与调用 sink 必须在同一临界区内。 */
    private final StringBuilder pending = new StringBuilder();
    /** 缓冲中第一片增量，沿用其 seq/source/timestamp 作为吐出事件的元信息（服务端会重编 seq）。 */
    private SessionEvent head;
    private long lastFlushAt = System.currentTimeMillis();

    public RunnerDeltaAggregator(Tuning tuning, Consumer<SessionEvent> sink) {
        this.tuning = tuning;
        this.sink = sink;
    }

    /** 收口一条事件：{@code text_delta} 进缓冲按阈值吐；其余事件先强制 flush，再原样交出。 */
    public void accept(SessionEvent ev) {
        if (!"text_delta".equals(ev.type())) {
            synchronized (pending) {
                flushLocked();
                sink.accept(ev);
            }
            return;
        }
        String text = ev.content();
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (pending) {
            if (pending.length() == 0) {
                head = ev;
            }
            pending.append(text);
            if (pending.length() >= tuning.flushChars()
                    || System.currentTimeMillis() - lastFlushAt >= tuning.flushMs()
                    || pending.length() >= tuning.maxBufferChars()) {
                flushLocked();
            }
        }
    }

    /**
     * 强制吐出积压正文。会话退出前必须调，且要排在一切可能阻塞的收尾动作（产出回传、工作区
     * finalizer，最长 30s）**之前**，否则最后一段正文会在光标空转半分钟后才出现。
     */
    public void flush() {
        synchronized (pending) {
            flushLocked();
        }
    }

    /** 调用方须持有 {@link #pending} 锁——判定与 send 同锁，这是顺序不变量的实现方式。 */
    private void flushLocked() {
        lastFlushAt = System.currentTimeMillis();
        if (pending.length() == 0) {
            return;
        }
        String text = pending.toString();
        pending.setLength(0);
        SessionEvent first = head;
        head = null;
        sink.accept(SessionEvent.of(first.seq(), "text_delta", text, first.source(),
                first.timestamp(), Map.of()));
    }
}
