package com.devmind.knowledge.governance;

import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleServiceTest {

    private KnowledgeEntryRepository entryRepo;
    private LifecycleService service;

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        service = new LifecycleService(entryRepo);
    }

    @Test
    void groupsExpiredExpiringStaleAndMissingOwnerIndependently() {
        Instant now = Instant.now();
        Ref expired = new Ref(1L, "已过期", now.minus(30, ChronoUnit.DAYS), null,
                now, "alice");
        Ref expiringSoon = new Ref(2L, "将到期", now.plus(5, ChronoUnit.DAYS), null,
                now, "bob");
        Ref stale = new Ref(3L, "久未复核", null, now.minus(400, ChronoUnit.DAYS),
                now.minus(400, ChronoUnit.DAYS), "carol");
        Ref noOwner = new Ref(4L, "缺负责人", null, null, null, "  ");
        when(entryRepo.findLifecycleRefs()).thenReturn(List.of(
                expired, expiringSoon, stale, noOwner));

        LifecycleReport report = service.report(14, 180, 100);

        assertEquals(1, report.expiredCount());
        assertEquals(List.of(1L), report.expired().stream().map(LifecycleReport.Item::id).toList());
        assertEquals(1, report.expiringCount());
        assertEquals(List.of(2L), report.expiring().stream().map(LifecycleReport.Item::id).toList());
        assertEquals(1, report.staleCount());
        assertEquals(List.of(3L), report.stale().stream().map(LifecycleReport.Item::id).toList());
        assertEquals(1, report.missingOwnerCount());
        assertEquals(List.of(4L), report.missingOwner().stream().map(LifecycleReport.Item::id).toList());
        assertEquals(14, report.expiringDays());
        assertEquals(180, report.staleDays());
    }

    @Test
    void staleGroupTreatsUpdatedAtAsFallbackReviewTime() {
        Instant now = Instant.now();
        Ref neverReviewed = new Ref(9L, "从未复核", null, now.minus(365, ChronoUnit.DAYS),
                null, "alice");
        when(entryRepo.findLifecycleRefs()).thenReturn(List.of(neverReviewed));

        LifecycleReport report = service.report(14, 180, 100);

        assertEquals(1, report.staleCount(), "没有复核时间时以更新时间判断是否久未复核");
    }

    @Test
    void countsStayCompleteWhenListIsTrimmedByLimit() {
        Instant now = Instant.now();
        when(entryRepo.findLifecycleRefs()).thenReturn(List.of(
                new Ref(1L, "a", now.plus(1, ChronoUnit.DAYS), null, now, "x"),
                new Ref(2L, "b", now.plus(2, ChronoUnit.DAYS), null, now, "x"),
                new Ref(3L, "c", now.plus(3, ChronoUnit.DAYS), null, now, "x")));

        LifecycleReport report = service.report(14, 180, 2);

        assertEquals(3, report.expiringCount(), "总数不受 limit 截断");
        assertEquals(2, report.expiring().size(), "列表按 limit 截断");
        assertEquals(List.of(1L, 2L), report.expiring().stream()
                .map(LifecycleReport.Item::id).toList(), "按到期时间升序");
    }

    @Test
    void invalidArgumentsFallBackToDefaultsAndClamp() {
        when(entryRepo.findLifecycleRefs()).thenReturn(List.of());

        LifecycleReport defaults = service.report(null, null, null);
        assertEquals(14, defaults.expiringDays());
        assertEquals(180, defaults.staleDays());
        assertEquals(100, defaults.limit());

        LifecycleReport clamped = service.report(0, 0, 0);
        assertEquals(1, clamped.expiringDays());
        assertEquals(1, clamped.staleDays());
        assertEquals(1, clamped.limit(), "limit 下限 1");
        assertTrue(clamped.expired().isEmpty());
    }

    private static final class Ref implements KnowledgeEntryRepository.LifecycleRef {
        private final Long id;
        private final String name;
        private final Instant validTo;
        private final Instant lastReviewedAt;
        private final Instant updatedAt;
        private final String owner;

        private Ref(Long id, String name, Instant validTo, Instant lastReviewedAt,
                    Instant updatedAt, String owner) {
            this.id = id;
            this.name = name;
            this.validTo = validTo;
            this.lastReviewedAt = lastReviewedAt;
            this.updatedAt = updatedAt;
            this.owner = owner;
        }

        @Override public Long getId() { return id; }
        @Override public Long getKbId() { return 1L; }
        @Override public String getName() { return name; }
        @Override public String getDocType() { return "TECH"; }
        @Override public String getOwner() { return owner; }
        @Override public Instant getValidTo() { return validTo; }
        @Override public Instant getLastReviewedAt() { return lastReviewedAt; }
        @Override public Instant getUpdatedAt() { return updatedAt; }
        @Override public String getStatus() { return "active"; }
    }
}
