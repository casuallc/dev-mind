package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventDedupTest {

    @Test
    void firstSeenTrueDuplicateFalse() {
        EventDedup d = new EventDedup();
        assertTrue(d.markIfFirst("feishu", "evt-1"));
        assertFalse(d.markIfFirst("feishu", "evt-1"));
        // 不同 channel 互不影响
        assertTrue(d.markIfFirst("wecom", "evt-1"));
        assertTrue(d.markIfFirst("feishu", "evt-2"));
    }

    @Test
    void blankEventIdNeverDeduplicated() {
        EventDedup d = new EventDedup();
        assertTrue(d.markIfFirst("feishu", null));
        assertTrue(d.markIfFirst("feishu", null));
        assertTrue(d.markIfFirst("feishu", ""));
    }
}
