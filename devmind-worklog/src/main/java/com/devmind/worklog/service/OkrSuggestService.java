package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.OkrAdoptRequest;
import com.devmind.worklog.dto.OkrAdoptResult;
import com.devmind.worklog.dto.OkrDraftView;
import com.devmind.worklog.dto.SuggestedObjective;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogOkrDraftEntity;
import com.devmind.worklog.repo.WorklogOkrDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * CAP-93 OKR 草稿（FR-03/FR-04）：生成产物解析后 upsert 草稿（user+period 唯一，重生成
 * 覆盖回 DRAFT）；采纳 = 人工确认后的树逐条走 {@link WorklogGoalService#create} 校验链
 * 两阶段建 O→KR（单事务，任一条失败整体回滚），成功置 ADOPTED；丢弃置 DISCARDED。
 * 按人隔离（他人 404，同目标域口径）。
 */
@Service
public class OkrSuggestService {

    /** 单次采纳上限（O+KR 总数，防爆同解析器口径） */
    static final int MAX_ADOPT_ITEMS = 40;

    private final WorklogOkrDraftRepository draftRepo;
    private final WorklogGoalService goalService;
    private final ObjectMapper mapper;

    public OkrSuggestService(WorklogOkrDraftRepository draftRepo, WorklogGoalService goalService,
                             ObjectMapper mapper) {
        this.draftRepo = draftRepo;
        this.goalService = goalService;
        this.mapper = mapper;
    }

    /** 最新草稿（无 → exists=false 空视图，同报告口径 200 不报 404）。 */
    public OkrDraftView latestDraft(String username, String period) {
        return draftRepo.findByUserIdAndPeriod(username, period)
                .map(this::toView)
                .orElse(OkrDraftView.empty(period));
    }

    /**
     * 生成落草稿（{@link WorklogOutputMirror} 调用）：upsert user+period 唯一行——
     * 重生成覆盖原文与解析树、状态回 DRAFT（采纳/丢弃后允许 force 推翻重来）。
     *
     * @return 解析出的 O 个数（通知文案用）
     */
    public int saveDraft(String owner, String period, String contentMd,
                         List<SuggestedObjective> items, String sessionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        WorklogOkrDraftEntity e = draftRepo.findByUserIdAndPeriod(owner, period)
                .orElseGet(() -> {
                    WorklogOkrDraftEntity n = new WorklogOkrDraftEntity();
                    n.setUserId(owner);
                    n.setPeriod(period);
                    n.setCreatedAt(now);
                    return n;
                });
        e.setScope(WorklogQuarters.isQuarter(period)
                ? WorklogGoalEntity.SCOPE_QUARTER : WorklogGoalEntity.SCOPE_YEAR);
        e.setContentMd(contentMd == null ? "" : contentMd);
        e.setItemsJson(mapper.writeValueAsString(items));
        e.setSessionId(sessionId);
        e.setStatus(WorklogOkrDraftEntity.STATUS_DRAFT);
        e.setUpdatedAt(now);
        draftRepo.save(e);
        return items.size();
    }

    /**
     * 采纳草稿（FR-04）：objectives 为人工确认/编辑后的树；两阶段先 O 后 KR，逐条走
     * {@link WorklogGoalService#create} 既有校验（KR 挂同周期 O、季度 O 挂同年年度 O 等），
     * 单事务任一条失败整体回滚。scope/period 锁定草稿的（身份属性）。
     */
    @Transactional
    public OkrAdoptResult adopt(String username, OkrAdoptRequest req) {
        WorklogOkrDraftEntity draft = draftRepo.findByIdAndUserId(req.draftId(), username)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "草稿不存在: " + req.draftId()));
        if (!WorklogOkrDraftEntity.STATUS_DRAFT.equals(draft.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "草稿已" + (WorklogOkrDraftEntity.STATUS_ADOPTED.equals(draft.getStatus())
                            ? "采纳" : "丢弃") + "，如需重来请 force 重新生成");
        }
        long total = req.objectives().size()
                + req.objectives().stream().mapToLong(o -> o.krs() == null ? 0 : o.krs().size()).sum();
        if (total > MAX_ADOPT_ITEMS) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "单次采纳上限 " + MAX_ADOPT_ITEMS + " 项");
        }
        String scope = draft.getScope();
        String period = draft.getPeriod();
        boolean quarter = WorklogGoalEntity.SCOPE_QUARTER.equals(scope);
        List<GoalView> created = new ArrayList<>();
        for (SuggestedObjective o : req.objectives()) {
            GoalView ov = goalService.create(new GoalRequest(scope, period,
                    WorklogGoalEntity.KIND_OBJECTIVE,
                    quarter ? o.parentYearGoalId() : null, o.title(), o.content(), o.weight(),
                    null, null, null));
            created.add(ov);
            if (o.krs() == null) {
                continue;
            }
            for (SuggestedObjective.SuggestedKr kr : o.krs()) {
                created.add(goalService.create(new GoalRequest(scope, period,
                        WorklogGoalEntity.KIND_KEY_RESULT, ov.id(), kr.title(), kr.content(),
                        kr.weight(), null, null, null)));
            }
        }
        draft.setStatus(WorklogOkrDraftEntity.STATUS_ADOPTED);
        draft.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        draftRepo.save(draft);
        return new OkrAdoptResult(created.size(), created);
    }

    /** 丢弃草稿（ADOPTED 不可丢弃——已落库的目标走目标域删除）。 */
    public void discard(String username, Long draftId) {
        WorklogOkrDraftEntity draft = draftRepo.findByIdAndUserId(draftId, username)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "草稿不存在: " + draftId));
        if (WorklogOkrDraftEntity.STATUS_ADOPTED.equals(draft.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "草稿已采纳（目标已创建），如需撤销请到目标页删除对应目标");
        }
        draft.setStatus(WorklogOkrDraftEntity.STATUS_DISCARDED);
        draft.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        draftRepo.save(draft);
    }

    private OkrDraftView toView(WorklogOkrDraftEntity e) {
        List<SuggestedObjective> items = e.getItemsJson() == null || e.getItemsJson().isBlank()
                ? List.of()
                : mapper.readValue(e.getItemsJson(), new TypeReference<>() {
                });
        return new OkrDraftView(true, e.getId(), e.getScope(), e.getPeriod(), e.getContentMd(),
                items, e.getSessionId(), e.getStatus(), e.getUpdatedAt());
    }
}
