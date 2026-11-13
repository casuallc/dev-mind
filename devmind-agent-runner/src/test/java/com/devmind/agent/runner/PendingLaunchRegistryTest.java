package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingLaunchRegistry}：launch 两段式的受理窗口——缓冲/标记/出清的顺序与竞态语义。
 */
class PendingLaunchRegistryTest {

    @Test
    void beginRejectsBlankAndDuplicate() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        assertThrows(IllegalStateException.class, () -> reg.begin(null));
        assertThrows(IllegalStateException.class, () -> reg.begin(" "));
        reg.begin("s1");
        assertThrows(IllegalStateException.class, () -> reg.begin("s1"));
        assertTrue(reg.isPending("s1"));
        assertEquals(1, reg.size());
        assertTrue(reg.ids().contains("s1"));
    }

    @Test
    void bufferThenDrainFlushesInOrder() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        reg.begin("s1");
        assertTrue(reg.bufferStdin("s1", "line1"));
        assertTrue(reg.bufferStdin("s1", "line2"));
        List<String> flushed = new ArrayList<>();
        PendingLaunchRegistry.Outcome outcome = reg.drain("s1", flushed::add);
        assertEquals(List.of("line1", "line2"), flushed);
        assertFalse(outcome.cancelled());
        assertFalse(outcome.closeOnStart());
        assertFalse(reg.isPending("s1"));
        // 出清后再来的输入转直写
        assertFalse(reg.bufferStdin("s1", "line3"));
        assertNull(reg.drain("s1", flushed::add));
    }

    @Test
    void bufferOverflowDropsOldest() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        reg.begin("s1");
        for (int i = 0; i < PendingLaunchRegistry.MAX_BUFFERED_LINES + 5; i++) {
            assertTrue(reg.bufferStdin("s1", "line" + i));
        }
        List<String> flushed = new ArrayList<>();
        reg.drain("s1", flushed::add);
        assertEquals(PendingLaunchRegistry.MAX_BUFFERED_LINES, flushed.size());
        assertEquals("line5", flushed.get(0)); // 最旧 5 条被丢
    }

    @Test
    void cancelAndCloseOnStartFlagsSurviveDrain() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        reg.begin("s1");
        assertTrue(reg.cancel("s1"));
        assertTrue(reg.isCancelled("s1"));
        assertTrue(reg.markCloseOnStart("s1"));
        PendingLaunchRegistry.Outcome outcome = reg.drain("s1", l -> { });
        assertTrue(outcome.cancelled());
        assertTrue(outcome.closeOnStart());
    }

    @Test
    void unknownSessionFallsThrough() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        assertFalse(reg.bufferStdin("nope", "x"));
        assertFalse(reg.markCloseOnStart("nope"));
        assertFalse(reg.cancel("nope"));
        assertNull(reg.drain("nope", l -> { }));
        // 已移除（异常出清）按取消处理：准备线程不再继续
        assertTrue(reg.isCancelled("nope"));
    }

    @Test
    void removeDiscardsBufferedInput() {
        PendingLaunchRegistry reg = new PendingLaunchRegistry();
        reg.begin("s1");
        reg.bufferStdin("s1", "line1");
        reg.remove("s1");
        assertNull(reg.drain("s1", l -> { }));
        assertFalse(reg.bufferStdin("s1", "line2"));
    }
}
