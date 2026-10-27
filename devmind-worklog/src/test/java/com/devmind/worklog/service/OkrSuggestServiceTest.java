package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.OkrAdoptRequest;
import com.devmind.worklog.dto.OkrAdoptResult;
import com.devmind.worklog.dto.OkrDraftView;
import com.devmind.worklog.dto.SuggestedObjective;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogOkrDraftEntity;
import com.devmind.worklog.repo.WorklogOkrDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-93 FR-03/04 OkrSuggestService 单测（Mockito 直桩）：草稿 upsert 覆盖回 DRAFT、
 * 采纳两阶段建 O→KR（KR 挂新建 O 的 id）、非 DRAFT 拒采纳、归属 404、丢弃口径。
 */
class OkrSuggestServiceTest {

    private WorklogOkrDraftRepository draftRepo;
    private WorklogGoalService goalService;
    private OkrSuggestService service;

    @BeforeEach
    void setUp() {
        draftRepo = mock(WorklogOkrDraftRepository.class);
        goalService = mock(WorklogGoalService.class);
        service = new OkrSuggestService(draftRepo, goalService, new ObjectMapper());
    }

    private static GoalView goalView(long id, GoalRequest req) {
        WorklogGoalEntity e = new WorklogGoalEntity();
        e.setId(id);
        e.setUserId("u1");
        e.setScope(req.scope());
        e.setPeriod(req.period());
        e.setKind(req.kind());
        e.setParentId(req.parentId());
        e.setTitle(req.title());
        e.setCreatedAt(Instant.now());
        return GoalView.of(e, null);
    }

    @Test
    void 落草稿新建与重生成覆盖回DRAFT() {
        when(draftRepo.findByUserIdAndPeriod("u1", "2026-Q4")).thenReturn(Optional.empty());
        List<SuggestedObjective> items = List.of(new SuggestedObjective("目标一", null, 50, null,
                List.of(new SuggestedObjective.SuggestedKr("KR 一", null, null))));
        assertEquals(1, service.saveDraft("u1", "2026-Q4", "原文", items, "s1"));
        ArgumentCaptor<WorklogOkrDraftEntity> cap = ArgumentCaptor.forClass(WorklogOkrDraftEntity.class);
        verify(draftRepo).save(cap.capture());
        WorklogOkrDraftEntity saved = cap.getValue();
        assertEquals("QUARTER", saved.getScope());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals("s1", saved.getSessionId());
        assertTrue(saved.getItemsJson().contains("目标一"));

        // 重生成：命中已有行（即便 ADOPTED）→ 覆盖内容、状态回 DRAFT
        saved.setStatus(WorklogOkrDraftEntity.STATUS_ADOPTED);
        when(draftRepo.findByUserIdAndPeriod("u1", "2026-Q4")).thenReturn(Optional.of(saved));
        service.saveDraft("u1", "2026-Q4", "新原文", items, "s2");
        assertEquals(WorklogOkrDraftEntity.STATUS_DRAFT, saved.getStatus());
        assertEquals("新原文", saved.getContentMd());
        assertEquals("s2", saved.getSessionId());
    }

    @Test
    void 年度period推导YEARscope() {
        when(draftRepo.findByUserIdAndPeriod("u1", "2027")).thenReturn(Optional.empty());
        service.saveDraft("u1", "2027", "x", List.of(), "s1");
        ArgumentCaptor<WorklogOkrDraftEntity> cap = ArgumentCaptor.forClass(WorklogOkrDraftEntity.class);
        verify(draftRepo).save(cap.capture());
        assertEquals("YEAR", cap.getValue().getScope());
    }

    @Test
    void 无草稿返回existsFalse() {
        when(draftRepo.findByUserIdAndPeriod("u1", "2026-Q4")).thenReturn(Optional.empty());
        OkrDraftView v = service.latestDraft("u1", "2026-Q4");
        assertFalse(v.exists());
        assertEquals("2026-Q4", v.period());
    }

    @Test
    void 草稿视图解析itemsJson() {
        service.saveDraft("u1", "2026-Q4", "原文",
                List.of(new SuggestedObjective("目标一", null, 50, 7L,
                        List.of(new SuggestedObjective.SuggestedKr("KR 一", null, 30)))), "s1");
        ArgumentCaptor<WorklogOkrDraftEntity> cap = ArgumentCaptor.forClass(WorklogOkrDraftEntity.class);
        verify(draftRepo).save(cap.capture());
        when(draftRepo.findByUserIdAndPeriod("u1", "2026-Q4")).thenReturn(Optional.of(cap.getValue()));
        OkrDraftView v = service.latestDraft("u1", "2026-Q4");
        assertTrue(v.exists());
        assertEquals(1, v.objectives().size());
        assertEquals("目标一", v.objectives().get(0).title());
        assertEquals(7L, v.objectives().get(0).parentYearGoalId());
        assertEquals(1, v.objectives().get(0).krs().size());
    }

    @Test
    void 采纳两阶段建O后KR挂新O() {
        WorklogOkrDraftEntity draft = new WorklogOkrDraftEntity();
        draft.setId(1L);
        draft.setUserId("u1");
        draft.setScope("QUARTER");
        draft.setPeriod("2026-Q4");
        draft.setStatus(WorklogOkrDraftEntity.STATUS_DRAFT);
        when(draftRepo.findByIdAndUserId(1L, "u1")).thenReturn(Optional.of(draft));
        AtomicLong idSeq = new AtomicLong(100);
        when(goalService.create(any())).thenAnswer(inv -> goalView(idSeq.getAndIncrement(),
                inv.getArgument(0)));

        OkrAdoptResult r = service.adopt("u1", new OkrAdoptRequest(1L, List.of(
                new SuggestedObjective("目标一", null, 50, 7L,
                        List.of(new SuggestedObjective.SuggestedKr("KR 一", null, null),
                                new SuggestedObjective.SuggestedKr("KR 二", null, 20))))));

        assertEquals(3, r.created());
        ArgumentCaptor<GoalRequest> reqs = ArgumentCaptor.forClass(GoalRequest.class);
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.times(3)).create(reqs.capture());
        List<GoalRequest> all = reqs.getAllValues();
        assertEquals("OBJECTIVE", all.get(0).kind());
        assertEquals("2026-Q4", all.get(0).period());
        assertEquals(7L, all.get(0).parentId(), "季度 O 透传年度父 id");
        assertEquals(100L, all.get(1).parentId(), "KR 挂新建 O 的 id");
        assertEquals(100L, all.get(2).parentId());
        assertEquals(WorklogOkrDraftEntity.STATUS_ADOPTED, draft.getStatus());
    }

    @Test
    void 采纳非DRAFT拒收() {
        WorklogOkrDraftEntity draft = new WorklogOkrDraftEntity();
        draft.setId(1L);
        draft.setUserId("u1");
        draft.setStatus(WorklogOkrDraftEntity.STATUS_ADOPTED);
        when(draftRepo.findByIdAndUserId(1L, "u1")).thenReturn(Optional.of(draft));
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.adopt("u1", new OkrAdoptRequest(1L,
                        List.of(new SuggestedObjective("x", null, null, null, null)))));
        assertTrue(e.getMessage().contains("已采纳"));
    }

    @Test
    void 采纳他人草稿404() {
        when(draftRepo.findByIdAndUserId(1L, "u2")).thenReturn(Optional.empty());
        assertThrows(DevMindException.class,
                () -> service.adopt("u2", new OkrAdoptRequest(1L,
                        List.of(new SuggestedObjective("x", null, null, null, null)))));
    }

    @Test
    void 丢弃草稿与已采纳拒弃() {
        WorklogOkrDraftEntity draft = new WorklogOkrDraftEntity();
        draft.setId(1L);
        draft.setUserId("u1");
        draft.setStatus(WorklogOkrDraftEntity.STATUS_DRAFT);
        when(draftRepo.findByIdAndUserId(1L, "u1")).thenReturn(Optional.of(draft));
        service.discard("u1", 1L);
        assertEquals(WorklogOkrDraftEntity.STATUS_DISCARDED, draft.getStatus());

        draft.setStatus(WorklogOkrDraftEntity.STATUS_ADOPTED);
        assertThrows(DevMindException.class, () -> service.discard("u1", 1L));
    }
}
