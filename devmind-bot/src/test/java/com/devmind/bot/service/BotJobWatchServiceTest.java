package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devmind.bot.model.BotJobWatchEntity;
import com.devmind.bot.repo.BotJobWatchRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** CAP-75 FR-25：登记（SESSION 路由 / 覆盖旧行 / 空参忽略）、一次性消费、登记失败不抛。 */
class BotJobWatchServiceTest {

    private final BotJobWatchRepository repo = mock(BotJobWatchRepository.class);
    private final BotSessionWatchService sessionWatches = mock(BotSessionWatchService.class);
    private final BotJobWatchService service = new BotJobWatchService(repo, sessionWatches);

    @Test
    void registersJobWatchReplacingExisting() {
        BotJobWatchEntity old = new BotJobWatchEntity();
        when(repo.findByEntityTypeAndEntityId("BUILD", "12")).thenReturn(Optional.of(old));

        service.watch("BUILD", "12", "feishu", "chat-1", "触发构建");

        verify(repo).delete(old);
        ArgumentCaptor<BotJobWatchEntity> saved = ArgumentCaptor.forClass(BotJobWatchEntity.class);
        verify(repo).save(saved.capture());
        assertEquals("BUILD", saved.getValue().getEntityType());
        assertEquals("12", saved.getValue().getEntityId());
        assertEquals("feishu", saved.getValue().getChannel());
        assertEquals("chat-1", saved.getValue().getExternalChatId());
        assertEquals("触发构建", saved.getValue().getLabel());
    }

    @Test
    void sessionEntityRoutesToSessionWatch() {
        service.watch("SESSION", "s-abc", "feishu", "chat-1", "修登录页");

        verify(sessionWatches).register("s-abc", "feishu", "chat-1", "修登录页");
        verify(repo, never()).save(any());
    }

    @Test
    void blankArgumentsAreIgnored() {
        service.watch("BUILD", "", "feishu", "chat-1", "x");
        service.watch("BUILD", "12", null, "chat-1", "x");
        service.watch("BUILD", "12", "feishu", " ", "x");

        verify(repo, never()).save(any());
        verify(sessionWatches, never()).register(any(), any(), any(), any());
    }

    @Test
    void registrationFailureIsLoggedNotThrown() {
        when(repo.findByEntityTypeAndEntityId("BUILD", "12"))
                .thenThrow(new IllegalStateException("db down"));

        service.watch("BUILD", "12", "feishu", "chat-1", "触发构建"); // 不抛

        verify(repo, never()).save(any());
    }

    @Test
    void takeConsumesOneShot() {
        BotJobWatchEntity w = new BotJobWatchEntity();
        when(repo.findByEntityTypeAndEntityId("TEST_RUN", "9")).thenReturn(Optional.of(w));

        Optional<BotJobWatchEntity> taken = service.take("TEST_RUN", "9");

        assertTrue(taken.isPresent());
        verify(repo).delete(w);
    }

    @Test
    void takeMissesWhenNoWatch() {
        when(repo.findByEntityTypeAndEntityId("BUILD", "99")).thenReturn(Optional.empty());

        assertTrue(service.take("BUILD", "99").isEmpty());
        verify(repo, never()).delete(any(BotJobWatchEntity.class));
    }
}
