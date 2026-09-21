package com.devmind.chat.runtime;

import com.devmind.chat.config.ChatProperties;
import com.devmind.chat.model.ChatEventEntity;
import com.devmind.chat.repo.ChatEventRepository;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.agent.runtime.RuntimeEventSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * CAP-30 问答事件批量落库：事件先入内存队列，每隔 eventFlushMs 批量写 chat_events，
 * 避免高频事件拖垮进程读取线程（复刻 SessionEventSaver 模式）。
 *
 * <p>CAP-49：队列<b>有界</b>（10k）。原来的无界队列让"队列已满"分支成了死代码——DB 故障或
 * 慢库叠加流式会话就是内存无界增长，一个进程能把整台机器拖垮。丢事件比 OOM 好，且丢弃要计数告警，
 * 不能静默（事件是问答的全部证据，chat_events 少了一段用户是看得见的）。</p>
 */
@Component
public class ChatEventSaver implements RuntimeEventSink {

    private static final Logger log = LoggerFactory.getLogger(ChatEventSaver.class);

    /** 队列容量：按 5k 事件/秒的极端产出，够 2 秒缓冲；再堆就是落库侧真的病了 */
    private static final int MAX_QUEUE = 10_000;

    private final ChatEventRepository repo;
    private final ChatProperties props;
    private final ObjectMapper mapper;
    private final BlockingQueue<ChatEventEntity> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;
    private Thread flusher;

    public ChatEventSaver(ChatEventRepository repo, ChatProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void start() {
        if (flusher != null) {
            return;
        }
        flusher = Thread.ofVirtual().name("chat-event-flusher").start(() -> {
            while (running) {
                try {
                    drainAndSave();
                    Thread.sleep(props.getEventFlushMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("问答事件批量落库异常", e);
                }
            }
            // 关闭前排空
            drainAndSave();
        });
    }

    @Override
    public void offer(String sessionId, SessionEvent ev) {
        ChatEventEntity e = new ChatEventEntity();
        e.setChatId(sessionId);
        e.setSeq(ev.seq());
        e.setType(ev.type());
        e.setContent(ev.content());
        e.setSource(ev.source());
        if (ev.payload() != null && !ev.payload().isEmpty()) {
            try {
                e.setPayload(mapper.writeValueAsString(ev.payload()));
            } catch (Exception ex) {
                log.warn("payload 序列化失败: chat={} seq={} err={}", sessionId, ev.seq(), ex.getMessage());
            }
        }
        e.setCreatedAt(Instant.ofEpochMilli(ev.timestamp()));
        if (!queue.offer(e)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("问答事件落库队列已满（容量 {}），已丢弃 {} 条（落库跟不上产出，查 DB）: chat={} seq={}",
                        MAX_QUEUE, n, sessionId, ev.seq());
            }
        }
    }

    private void drainAndSave() {
        List<ChatEventEntity> batch = new ArrayList<>(200);
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
