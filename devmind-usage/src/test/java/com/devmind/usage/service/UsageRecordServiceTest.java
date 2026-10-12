package com.devmind.usage.service;

import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.common.agent.runtime.TurnUsage;
import com.devmind.common.usage.UsageRecordSink;
import com.devmind.session.model.SessionEntity;
import com.devmind.usage.model.UsageRecordEntity;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageRecordRepository;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 逐回合账本落库（{@link UsageRecordService}）的回归网：归属快照、可空字段直存
 * （不 coerce 0）、实体缺席照写不丢账、未知来源拒写、落库异常自吞（SPI 契约）。
 */
class UsageRecordServiceTest {

    private final List<UsageRecordEntity> saved = new ArrayList<>();
    private final Map<String, SessionEntity> sessions = new HashMap<>();
    private final Map<String, ChatSessionEntity> chats = new HashMap<>();
    private boolean saveThrows = false;

    private UsageRecordService service;

    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler));
    }

    @BeforeEach
    void setUp() {
        UsageRecordRepository recordRepo = proxy(UsageRecordRepository.class, (p, m, args) -> {
            if ("save".equals(m.getName())) {
                if (saveThrows) {
                    throw new RuntimeException("db down");
                }
                saved.add((UsageRecordEntity) args[0]);
                return args[0];
            }
            throw new UnsupportedOperationException(m.getName());
        });
        UsageSessionStatsRepository sessionRepo = proxy(UsageSessionStatsRepository.class,
                (p, m, args) -> "findById".equals(m.getName())
                        ? Optional.ofNullable(sessions.get((String) args[0]))
                        : null);
        UsageChatStatsRepository chatRepo = proxy(UsageChatStatsRepository.class,
                (p, m, args) -> "findById".equals(m.getName())
                        ? Optional.ofNullable(chats.get((String) args[0]))
                        : null);
        service = new UsageRecordService(recordRepo, sessionRepo, chatRepo);
    }

    private static TurnUsage usage() {
        return new TurnUsage(0.0123, 2000L, 1200L, 340L, 5600L, 700L);
    }

    @Test
    void 会话回合_归属快照自会话实体_字段直存() {
        SessionEntity s = new SessionEntity();
        s.setCreatedBy("alice");
        s.setModel("k3-256k");
        sessions.put("s1", s);

        service.record(UsageRecordSink.SOURCE_SESSION, "s1", usage());

        assertEquals(1, saved.size());
        UsageRecordEntity e = saved.get(0);
        assertEquals("SESSION", e.getSource());
        assertEquals("s1", e.getSessionId());
        assertEquals("alice", e.getOwner());
        assertEquals("k3-256k", e.getModel());
        assertEquals(0.0123, e.getCostUsd());
        assertEquals(2000L, e.getDurationMs());
        assertEquals(1200L, e.getInputTokens());
        assertEquals(340L, e.getOutputTokens());
        assertEquals(5600L, e.getCacheReadTokens());
        assertEquals(700L, e.getCacheCreationTokens());
        assertEquals(false, e.getCreatedAt() == null, "createdAt 必填");
    }

    @Test
    void 问答回合_归属快照自问答实体() {
        ChatSessionEntity c = new ChatSessionEntity();
        c.setCreatedBy("bob");
        chats.put("c1", c);

        service.record(UsageRecordSink.SOURCE_CHAT, "c1", usage());

        assertEquals(1, saved.size());
        assertEquals("CHAT", saved.get(0).getSource());
        assertEquals("bob", saved.get(0).getOwner());
        assertNull(saved.get(0).getModel(), "问答无模型时快照 null");
    }

    @Test
    void 可空字段直存不coerce0() {
        SessionEntity s = new SessionEntity();
        sessions.put("s1", s);
        // CAP-49 模型执行体：只有 durationMs
        service.record(UsageRecordSink.SOURCE_SESSION, "s1",
                new TurnUsage(null, 1500L, null, null, null, null));

        UsageRecordEntity e = saved.get(0);
        assertNull(e.getCostUsd());
        assertNull(e.getInputTokens());
        assertNull(e.getOutputTokens());
        assertNull(e.getCacheReadTokens());
        assertNull(e.getCacheCreationTokens());
        assertEquals(1500L, e.getDurationMs());
    }

    @Test
    void 会话实体缺席_照写null归属不丢账() {
        service.record(UsageRecordSink.SOURCE_SESSION, "ghost", usage());

        assertEquals(1, saved.size());
        assertNull(saved.get(0).getOwner());
        assertNull(saved.get(0).getModel());
    }

    @Test
    void 未知来源_拒写() {
        service.record("OTHER", "x1", usage());
        assertEquals(0, saved.size());
    }

    @Test
    void 落库异常自吞_不打断事件流() {
        saveThrows = true;
        assertDoesNotThrow(() -> service.record(UsageRecordSink.SOURCE_SESSION, "s1", usage()));
    }
}
