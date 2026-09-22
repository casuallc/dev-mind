package com.devmind.decision.record;

import com.devmind.common.decision.DecisionAnswer;
import com.devmind.common.decision.DecisionResult;
import com.devmind.decision.record.model.DecisionRecordEntity;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAP-55 FR-05 sink 实现（mock 掉 repo，连 writer 一起真实走）：
 * 盯三件事——建议/裁决写进同一行且互不覆盖、降级也留行、任何失败都不许抛给业务。
 */
class DecisionRecordStoreTest {

    private static final String CAPABILITY = "kb-proposal-triage";

    private final DecisionRecordRepository repo = mock(DecisionRecordRepository.class);

    private DecisionRecordStore store() {
        return new DecisionRecordStore(new DecisionRecordWriter(repo));
    }

    private Map<String, Map<String, Object>> questions() {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("type", "choice");
        layer.put("criteria", Map.of("global", "全局", "project", "项目", "discard", "放弃"));
        Map<String, Map<String, Object>> qs = new LinkedHashMap<>();
        qs.put("adopt_layer", layer);
        return qs;
    }

    private static DecisionResult result() {
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        answers.put("adopt_layer", new DecisionAnswer("choice", "project", null, null, 0.9,
                Map.of("global", 0.05, "project", 0.9, "discard", 0.05)));
        return DecisionResult.ok(answers, "multilingual", "non-Latin script", 320);
    }

    private DecisionRecordEntity row() {
        DecisionRecordEntity row = new DecisionRecordEntity();
        row.setId(1L);
        row.setCapability(CAPABILITY);
        row.setSubjectId("42");
        row.setDegraded(false);
        when(repo.findByCapabilityAndSubjectId(CAPABILITY, "42")).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void suggestionIsStoredAsVerbatimSnapshots() {
        row();
        Map<String, Object> state = Map.of("proposal_title", "构建失败先看日志末尾",
                "similar_entries", "（未召回到相似条目）");

        store().saveSuggestion(CAPABILITY, "42", state, questions(), result());

        ArgumentCaptor<DecisionRecordEntity> saved = ArgumentCaptor.forClass(DecisionRecordEntity.class);
        verify(repo).save(saved.capture());
        DecisionRecordEntity row = saved.getValue();
        assertTrue(row.getStateJson().contains("构建失败先看日志末尾"), row.getStateJson());
        assertTrue(row.getQuestionsJson().contains("adopt_layer"), row.getQuestionsJson());
        assertTrue(row.getModelAnswer().contains("\"choice\":\"project\""), row.getModelAnswer());
        assertTrue(row.getRoutingJson().contains("multilingual"), row.getRoutingJson());
        assertTrue(row.getRoutingJson().contains("non-Latin script"), row.getRoutingJson());
        assertEquals(320, row.getLatencyMs());
        assertNotNull(row.getSuggestedAt(), "建议产生时间要落：人工隔了多久才裁决是可用性指标");
    }

    @Test
    void degradedSuggestionStillGetsARow() {
        row();
        DecisionResult degraded = DecisionResult.degraded("边车连不上", 3000);

        store().saveSuggestion(CAPABILITY, "42", Map.of(), questions(), degraded);

        ArgumentCaptor<DecisionRecordEntity> saved = ArgumentCaptor.forClass(DecisionRecordEntity.class);
        verify(repo).save(saved.capture());
        assertTrue(saved.getValue().isDegraded(), "降级样本是评估可用性的第一手数据，不能只在日志里");
        assertEquals("边车连不上", saved.getValue().getDegradedReason());
    }

    @Test
    void verdictDoesNotWipeTheSuggestionAndViceVersaWritesSameRow() {
        DecisionRecordEntity row = row();
        row.setStateJson("{\"proposal_title\":\"旧建议\"}");
        row.setGoldJson("{\"adopt_layer\":\"global\"}");

        store().saveSuggestion(CAPABILITY, "42", Map.of("proposal_title", "新建议"), questions(), result());

        ArgumentCaptor<DecisionRecordEntity> saved = ArgumentCaptor.forClass(DecisionRecordEntity.class);
        verify(repo).save(saved.capture());
        assertEquals("{\"adopt_layer\":\"global\"}", saved.getValue().getGoldJson(),
                "重新分诊不能抹掉人工已经做过的裁决（那次决定已经发生了）");
        assertTrue(saved.getValue().getStateJson().contains("新建议"));
    }

    @Test
    void verdictOnAVerdictOnlyProposalCreatesTheRow() {
        when(repo.findByCapabilityAndSubjectId(CAPABILITY, "42")).thenReturn(Optional.empty());

        store().saveVerdict(CAPABILITY, "42", "adopt:project", Map.of("adopt_layer", "project"), "alice");

        ArgumentCaptor<DecisionRecordEntity> saved = ArgumentCaptor.forClass(DecisionRecordEntity.class);
        verify(repo).save(saved.capture());
        DecisionRecordEntity row = saved.getValue();
        assertEquals("adopt:project", row.getHumanAction());
        assertTrue(row.getGoldJson().contains("\"adopt_layer\":\"project\""), row.getGoldJson());
        assertEquals("alice", row.getDecidedBy());
        assertNotNull(row.getDecidedAt());
        assertNotNull(row.getCreatedAt(), "先裁决后（或没）分诊时，行也要有产生时间");
    }

    /** 唯一键竞态：首次插入撞冲突 → 让出后重试走 update（丢一条 gold 比多写一次贵得多） */
    @Test
    void uniqueKeyRaceIsRetriedOnce() {
        row();
        when(repo.save(any(DecisionRecordEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"))
                .thenAnswer(inv -> inv.getArgument(0));

        store().saveVerdict(CAPABILITY, "42", "reject", Map.of(), "alice");

        verify(repo, times(2)).save(any(DecisionRecordEntity.class));
        verify(repo, times(2)).findByCapabilityAndSubjectId(CAPABILITY, "42");
    }

    @Test
    void repositoryFailureIsSwallowed() {
        when(repo.findByCapabilityAndSubjectId(anyString(), anyString()))
                .thenThrow(new IllegalStateException("库连不上"));

        assertDoesNotThrow(() -> store().saveSuggestion(CAPABILITY, "42", Map.of(), questions(), result()));
        assertDoesNotThrow(() -> store().saveVerdict(CAPABILITY, "42", "reject", Map.of(), "alice"));
    }

    @Test
    void nullResultIsIgnored() {
        assertDoesNotThrow(() -> store().saveSuggestion(CAPABILITY, "42", Map.of(), questions(), null));

        verifyNoInteractions(repo);
    }
}
