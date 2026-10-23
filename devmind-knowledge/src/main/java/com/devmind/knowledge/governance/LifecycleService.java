package com.devmind.knowledge.governance;

import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * CAP-85 FR-29：到期/将到期/久未复核/缺负责人四组提醒。
 * 只读轻量投影（不加载正文），万级条目一次扫描；四组相互独立，便于分别处理。
 */
@Service
public class LifecycleService {

    private final KnowledgeEntryRepository entryRepo;

    public LifecycleService(KnowledgeEntryRepository entryRepo) {
        this.entryRepo = entryRepo;
    }

    public LifecycleReport report(Integer expiringDays, Integer staleDays, Integer limit) {
        int days = clamp(expiringDays, 3650, 14);
        int stale = clamp(staleDays, 3650, 180);
        int cap = clamp(limit, 500, 100);
        Instant now = Instant.now();
        Instant expiringUntil = now.plus(days, ChronoUnit.DAYS);
        Instant staleBefore = now.minus(stale, ChronoUnit.DAYS);

        List<LifecycleReport.Item> expired = new ArrayList<>();
        List<LifecycleReport.Item> expiring = new ArrayList<>();
        List<LifecycleReport.Item> staleItems = new ArrayList<>();
        List<LifecycleReport.Item> missingOwner = new ArrayList<>();
        long expiredCount = 0;
        long expiringCount = 0;
        long staleCount = 0;
        long missingOwnerCount = 0;

        for (KnowledgeEntryRepository.LifecycleRef ref : entryRepo.findLifecycleRefs()) {
            LifecycleReport.Item item = new LifecycleReport.Item(ref.getId(), ref.getKbId(),
                    ref.getName(), ref.getDocType(), ref.getOwner(), ref.getValidTo(),
                    ref.getLastReviewedAt(), ref.getUpdatedAt());
            Instant validTo = ref.getValidTo();
            if (validTo != null) {
                if (validTo.isBefore(now)) {
                    expiredCount++;
                    expired.add(item);
                } else if (!validTo.isAfter(expiringUntil)) {
                    expiringCount++;
                    expiring.add(item);
                }
            }
            Instant reviewed = ref.getLastReviewedAt() != null
                    ? ref.getLastReviewedAt() : ref.getUpdatedAt();
            if (reviewed != null && reviewed.isBefore(staleBefore)) {
                staleCount++;
                staleItems.add(item);
            }
            if (ref.getOwner() == null || ref.getOwner().isBlank()) {
                missingOwnerCount++;
                missingOwner.add(item);
            }
        }

        expired.sort(Comparator.comparing(LifecycleReport.Item::validTo));
        expiring.sort(Comparator.comparing(LifecycleReport.Item::validTo));
        staleItems.sort(Comparator.comparing(LifecycleService::reviewedAt));
        missingOwner.sort(Comparator.comparing(LifecycleReport.Item::updatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new LifecycleReport(now, days, stale, cap, expiredCount, expiringCount,
                staleCount, missingOwnerCount,
                trim(expired, cap), trim(expiring, cap), trim(staleItems, cap),
                trim(missingOwner, cap));
    }

    private static Instant reviewedAt(LifecycleReport.Item item) {
        return item.lastReviewedAt() != null ? item.lastReviewedAt() : item.updatedAt();
    }

    private static List<LifecycleReport.Item> trim(List<LifecycleReport.Item> items, int cap) {
        return items.size() <= cap ? items : new ArrayList<>(items.subList(0, cap));
    }

    private static int clamp(Integer value, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.min(max, Math.max(1, value));
    }
}
