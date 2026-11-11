package com.devmind.session.runtime;

import com.devmind.common.agent.runtime.RuntimeEventSink;
import com.devmind.session.config.SessionProperties;
import com.devmind.common.agent.SessionEvent;
import com.devmind.session.model.SessionEventEntity;
import com.devmind.session.repo.SessionEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 事件批量落库：所有会话事件先入内存队列，每隔 eventFlushMs 批量写 DB，
 * 避免高频事件拖垮进程读取线程。
 *
 * <p>2026-11-11 事故（194 环境）：EPSS 情报同步把堆顶满，凌晨 OOM 把 {@code event-flusher}
 * 线程打死——{@link OutOfMemoryError} 是 {@link Error}，原 {@code catch (Exception)} 兜不住，
 * 线程死后无人重启、无告警；无界队列继续吃下后续所有事件，session_events 整整断供两天，
 * 用户侧表现为「会话结束后只剩自己的输入」。三处加固：①循环 catch Throwable，OOM 后线程
 * 尽量活着并留下 error 日志；②{@link #offer} 检测线程死亡自动重启；③队列有界 + 丢弃计数
 * 告警（与 {@code ChatEventSaver} 同规约）——丢事件比 OOM 好，且不能静默。</p>
 */
@Component
public class SessionEventSaver implements RuntimeEventSink {

    private static final Logger log = LoggerFactory.getLogger(SessionEventSaver.class);

    /** 队列容量：按 5k 事件/秒的极端产出，够 2 秒缓冲；再堆就是落库侧真的病了 */
    private static final int MAX_QUEUE = 10_000;

    private final SessionEventRepository repo;
    private final SessionProperties props;
    private final ObjectMapper mapper;
    private final BlockingQueue<SessionEventEntity> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;
    private Thread flusher;

    public SessionEventSaver(SessionEventRepository repo, SessionProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void start() {
        if (flusher != null) {
            return;
        }
        flusher = Thread.ofVirtual().name("event-flusher").start(() -> {
            while (running) {
                try {
                    drainAndSave();
                    Thread.sleep(props.getEventFlushMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // Error（典型是 OOM）也要兜住：线程一死，offer 仍在静默进队，事件全丢且无告警
                    log.error("事件批量落库异常（线程保持存活）", e);
                }
            }
            // 关闭前排空
            drainAndSave();
        });
    }

    /** 落库线程被 OOM 等 Error 打死后自愈：重启线程并 error 告警（事故时队列已积压，日志要有声）。 */
    private synchronized void ensureFlusherAlive() {
        if (running && (flusher == null || !flusher.isAlive())) {
            log.error("事件落库线程已死亡，自动重启（此前应有 OOM/Error 日志）: 队列积压 {}", queue.size());
            flusher = null;
            start();
        }
    }

    @Override
    public void offer(String sessionId, SessionEvent ev) {
        ensureFlusherAlive();
        SessionEventEntity e = new SessionEventEntity();
        e.setSessionId(sessionId);
        e.setSeq(ev.seq());
        e.setType(ev.type());
        e.setContent(ev.content());
        e.setSource(ev.source());
        if (ev.payload() != null && !ev.payload().isEmpty()) {
            try {
                e.setPayload(mapper.writeValueAsString(ev.payload()));
            } catch (Exception ex) {
                log.warn("payload 序列化失败: session={} seq={} err={}", sessionId, ev.seq(), ex.getMessage());
            }
        }
        e.setCreatedAt(Instant.ofEpochMilli(ev.timestamp()));
        if (!queue.offer(e)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("事件落库队列已满（容量 {}），已丢弃 {} 条（落库跟不上产出，查 DB）: session={} seq={}",
                        MAX_QUEUE, n, sessionId, ev.seq());
            }
        }
    }

    private void drainAndSave() {
        List<SessionEventEntity> batch = new ArrayList<>(200);
        queue.drainTo(batch, 200);
        if (batch.isEmpty()) {
            return;
        }
        repo.saveAll(batch);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (flusher != null) {
            flusher.interrupt();
        }
    }
}
