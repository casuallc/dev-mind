package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
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
 */
@Component
public class SessionEventSaver {

    private static final Logger log = LoggerFactory.getLogger(SessionEventSaver.class);

    private final SessionEventRepository repo;
    private final SessionProperties props;
    private final ObjectMapper mapper;
    private final BlockingQueue<SessionEventEntity> queue = new LinkedBlockingQueue<>();
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
                } catch (Exception e) {
                    log.warn("事件批量落库异常", e);
                }
            }
            // 关闭前排空
            drainAndSave();
        });
    }

    public void offer(String sessionId, SessionEvent ev) {
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
            log.warn("事件队列已满，丢弃 1 条: session={} seq={}", sessionId, ev.seq());
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
