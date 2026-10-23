package com.devmind.knowledge.governance;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateDetectorTest {

    private KnowledgeEntryRepository entryRepo;
    private DuplicateDetector detector;

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        when(entryRepo.findMissingSimHashIds(any(Pageable.class))).thenReturn(List.of());
        detector = new DuplicateDetector(entryRepo);
    }

    @Test
    void identicalContentIsGroupedWithZeroDistance() {
        String text = "生产环境部署前必须核对数据库连接串与端口白名单。";
        String hash = SimHash.of(text);
        when(entryRepo.findDuplicateRefs()).thenReturn(List.of(
                ref(1L, "部署手册", hash), ref(2L, "部署手册（副本）", hash),
                ref(3L, "无关文档", SimHash.of("前端组件库的版本升级步骤与回滚方式。"))));

        DuplicateReport report = detector.report(3, 100);

        assertEquals(1, report.groups().size(), "只有重复的两篇成组");
        DuplicateReport.Group group = report.groups().get(0);
        assertEquals(2, group.entries().size());
        assertEquals(0, group.maxDistance(), "同指纹距离为 0");
        assertEquals(3, report.scanned());
        assertTrue(group.entries().stream().map(DuplicateReport.Item::id).toList()
                .containsAll(List.of(1L, 2L)));
    }

    @Test
    void threeCopiesStayOneGroupInsteadOfThreePairs() {
        String hash = SimHash.of("同一条常见问题的答案被复制了多份。");
        when(entryRepo.findDuplicateRefs()).thenReturn(List.of(
                ref(1L, "FAQ a", hash), ref(2L, "FAQ b", hash), ref(3L, "FAQ c", hash)));

        DuplicateReport report = detector.report(null, 100);

        assertEquals(1, report.groups().size());
        assertEquals(3, report.groups().get(0).entries().size());
        assertEquals(3, report.threshold(), "默认阈值 3");
    }

    @Test
    void backfillsMissingHashesAndReportsRemaining() {
        KnowledgeEntryEntity missing = new KnowledgeEntryEntity();
        missing.setId(7L);
        missing.setName("待补算");
        missing.setContentMd("需要补算 SimHash 的存量文档正文。");
        when(entryRepo.findMissingSimHashIds(any(Pageable.class))).thenReturn(List.of(7L));
        when(entryRepo.findById(7L)).thenReturn(Optional.of(missing));
        when(entryRepo.findDuplicateRefs()).thenReturn(List.of());
        when(entryRepo.countMissingSimHash()).thenReturn(4L);

        DuplicateReport report = detector.report(3, 50);

        assertEquals(SimHash.of(missing.getContentMd()), missing.getSimHash());
        verify(entryRepo).save(missing);
        assertEquals(4L, report.remainingWithoutHash(), "仍有未补算条目时报告给治理页");
        assertTrue(report.groups().isEmpty());
    }

    @Test
    void thresholdAndScanLimitAreClamped() {
        when(entryRepo.findDuplicateRefs()).thenReturn(List.of());

        assertEquals(16, detector.report(99, null).threshold(), "阈值上限 16");
        assertEquals(0, detector.report(0, null).threshold());
    }

    private static KnowledgeEntryRepository.DuplicateRef ref(Long id, String name, String hash) {
        return new DuplicateRef(id, name, hash);
    }

    private record DuplicateRef(Long id, String name, String simHash)
            implements KnowledgeEntryRepository.DuplicateRef {
        @Override public Long getId() { return id; }
        @Override public Long getKbId() { return 1L; }
        @Override public String getName() { return name; }
        @Override public String getDocType() { return "TECH"; }
        @Override public Instant getUpdatedAt() { return Instant.now(); }
        @Override public String getSimHash() { return simHash; }
    }
}
