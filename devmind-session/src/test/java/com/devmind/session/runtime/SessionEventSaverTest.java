package com.devmind.session.runtime;

import com.devmind.common.agent.SessionEvent;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEventEntity;
import com.devmind.session.repo.SessionEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-11 事故回归（194 环境 OOM 打死 event-flusher 后事件断供两天）：
 * ①flusher 线程死亡后 {@code offer} 自愈重启，事件恢复落库；
 * ②队列有界（10k）——落库侧卡住时丢弃并计数，不无界堆内存。
 */
class SessionEventSaverTest {

    private SessionEventSaver saver;

    @AfterEach
    void tearDown() {
        if (saver != null) {
            saver.stop();
        }
    }

    /** saveAll 全收集的假仓库；saveAllBlock 非空时 saveAll 挂起（模拟落库侧卡死）。 */
    private SessionEventRepository fakeRepo(List<SessionEventEntity> saved, CountDownLatch saveAllBlock) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getName().equals("saveAll") && args != null && args.length == 1) {
                if (saveAllBlock != null) {
                    saveAllBlock.await(30, TimeUnit.SECONDS);
                }
                @SuppressWarnings("unchecked")
                List<SessionEventEntity> batch = (List<SessionEventEntity>) args[0];
                synchronized (saved) {
                    saved.addAll(batch);
                }
                return batch;
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == long.class || rt == int.class) return 0;
            if (rt.isAssignableFrom(List.class)) return new ArrayList<>();
            if (rt == java.util.Optional.class) return java.util.Optional.empty();
            return null;
        };
        return (SessionEventRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SessionEventRepository.class}, h);
    }

    private SessionEventSaver newSaver(SessionEventRepository repo, int flushMs) {
        SessionProperties props = new SessionProperties();
        props.setEventFlushMs(flushMs);
        saver = new SessionEventSaver(repo, props, JsonMapper.builder().build());
        return saver;
    }

    private static SessionEvent ev(long seq) {
        return SessionEvent.of(seq, "assistant", "正文" + seq, "agent",
                System.currentTimeMillis(), Map.of());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static long getLongField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((AtomicLong) f.get(target)).get();
    }

    private static boolean await(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pred.get()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pred.get();
    }

    @Test
    void flusher线程死亡后offer自愈重启并恢复落库() throws Exception {
        List<SessionEventEntity> saved = new ArrayList<>();
        SessionEventSaver s = newSaver(fakeRepo(saved, null), 50);
        s.start();

        // 模拟 OOM 打死线程：换一个已终止的线程进去
        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join();
        setField(s, "flusher", dead);

        s.offer("s1", ev(1));
        assertTrue(await(() -> {
            synchronized (saved) {
                return saved.size() == 1;
            }
        }, 3000), "自愈重启的 flusher 应在 3s 内把事件落库");
        assertEquals("s1", saved.get(0).getSessionId());
    }

    @Test
    void 落库卡死时队列有界丢弃并计数() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        try {
            List<SessionEventEntity> saved = new ArrayList<>();
            // saveAll 挂起：flusher 首轮 drain 走最多 200 条后卡死，队列只能再收 10k
            SessionEventSaver s = newSaver(fakeRepo(saved, block), 10);
            s.start();
            int total = 10_500;
            for (int i = 0; i < total; i++) {
                s.offer("s1", ev(i));
            }
            long dropped = getLongField(s, "dropped");
            // 至少 10500-10000-200=300 条必须被丢弃（flusher 可能还没 drain，上界 500）
            assertTrue(dropped >= 300 && dropped <= 500, "丢弃计数应在 [300,500]，实际 " + dropped);
        } finally {
            block.countDown();
        }
    }
}
