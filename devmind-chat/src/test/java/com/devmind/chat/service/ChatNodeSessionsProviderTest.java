package com.devmind.chat.service;

import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.chat.repo.ChatSessionRepository;
import com.devmind.common.agent.AgentNodeSessionsProvider.ActiveSessionInfo;
import com.devmind.common.agent.runtime.SessionState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ChatNodeSessionsProvider}：kind=CHAT、title 直取问答标题。无 Spring/Mockito，手工 fake。 */
class ChatNodeSessionsProviderTest {

    @Test
    void mapsActiveChats() {
        ChatSessionEntity e = new ChatSessionEntity();
        e.setId("c1");
        e.setTitle("帮看 NPE");
        e.setStatus(SessionState.WAITING_INPUT.name());
        e.setCreatedBy("u2");
        e.setCreatedAt(Instant.parse("2026-09-09T02:00:00Z"));
        ChatSessionRepository repo = (ChatSessionRepository) Proxy.newProxyInstance(
                ChatNodeSessionsProviderTest.class.getClassLoader(),
                new Class<?>[]{ChatSessionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByAgentNodeIdAndStatusIn")) {
                        return List.of(e);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        List<ActiveSessionInfo> list = new ChatNodeSessionsProvider(repo).activeSessions("5");

        assertEquals(1, list.size());
        ActiveSessionInfo info = list.get(0);
        assertEquals("c1", info.sessionId());
        assertEquals("CHAT", info.kind());
        assertEquals("帮看 NPE", info.title());
        assertEquals("WAITING_INPUT", info.status());
        assertEquals("u2", info.createdBy());
    }
}
