package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.devmind.bot.config.BotProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** CAP-75 FR-24 定时清理：保留期内硬删 + watch 残留兜底（保留期 0 只跳过交互日志）。 */
class BotInteractionCleanupTaskTest {

    private final BotInteractionCleanupService cleanupService =
            mock(BotInteractionCleanupService.class);
    private final BotSessionWatchService watchService = mock(BotSessionWatchService.class);
    private final BotJobWatchService jobWatchService = mock(BotJobWatchService.class);
    private final BotProperties props = new BotProperties();

    private BotInteractionCleanupTask task() {
        return new BotInteractionCleanupTask(cleanupService, watchService, jobWatchService, props);
    }

    @Test
    void purgesInteractionsAndSweepsStaleWatches() {
        props.setInteractionRetentionDays(30);
        task().cleanup();
        verify(cleanupService).purge(30);
        // watch 残留固定 7 天：订阅本该在实体终态时就消费掉
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(watchService).sweepOlderThan(cutoff.capture());
        long days = Duration.between(cutoff.getValue(), Instant.now()).toDays();
        assertTrue(days == 6 || days == 7, "sweep 截止点应为 7 天前，实际 " + days + " 天");
        // FR-25：作业 watch 同口径兜底
        verify(jobWatchService).sweepOlderThan(any(Instant.class));
    }

    @Test
    void retentionZeroSkipsInteractionsButStillSweepsWatches() {
        props.setInteractionRetentionDays(0);
        task().cleanup();
        verify(cleanupService, never()).purge(anyInt());
        verify(watchService).sweepOlderThan(any(Instant.class));
        verify(jobWatchService).sweepOlderThan(any(Instant.class));
    }

    @Test
    void usesConfiguredRetentionDays() {
        props.setInteractionRetentionDays(7);
        task().cleanup();
        verify(cleanupService).purge(7);
    }
}
