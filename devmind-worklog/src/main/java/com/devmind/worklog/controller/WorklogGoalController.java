package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.GoalCarryRequest;
import com.devmind.worklog.dto.GoalCarryResult;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.LinkableKrView;
import com.devmind.worklog.dto.OkrAdoptRequest;
import com.devmind.worklog.dto.OkrAdoptResult;
import com.devmind.worklog.dto.OkrDraftIdRequest;
import com.devmind.worklog.dto.OkrDraftView;
import com.devmind.worklog.dto.OkrSuggestRequest;
import com.devmind.worklog.dto.ReportLaunch;
import com.devmind.worklog.service.OkrSuggestService;
import com.devmind.worklog.service.ReportService;
import com.devmind.worklog.service.WorklogGoalService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** CAP-91 目标 REST + CAP-93 OKR AI 生成（草稿确认）REST（仅本人数据，owner 强制，越权 404）。 */
@RestController
@RequestMapping("/api/worklog/goals")
public class WorklogGoalController {

    private final WorklogGoalService service;
    private final ReportService reportService;
    private final OkrSuggestService okrSuggestService;
    private final IdentityService identity;

    public WorklogGoalController(WorklogGoalService service, ReportService reportService,
                                 OkrSuggestService okrSuggestService, IdentityService identity) {
        this.service = service;
        this.reportService = reportService;
        this.okrSuggestService = okrSuggestService;
        this.identity = identity;
    }

    /** 整年目标树：年度 O（嵌套年度 KR）+ Q1~Q4 四季区（O 嵌套 KR，KR 附派生进度数据）。 */
    @GetMapping("/tree")
    public GoalTreeView tree(@RequestParam int year) {
        return service.tree(year);
    }

    /** 计划项抽屉可关联 KR：该日所在季的 ACTIVE KEY_RESULT。 */
    @GetMapping("/linkable-krs")
    public List<LinkableKrView> linkableKrs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.linkableKrs(date);
    }

    /** CAP-93 FR-01 AI 生成 OKR 草稿（幂等：目标周期已有目标且非 force → reused=true）。 */
    @PostMapping("/suggest")
    public ReportLaunch suggestOkr(@Valid @RequestBody OkrSuggestRequest req) {
        return reportService.suggestOkr(identity.currentActor(), req.scope(), req.period(),
                Boolean.TRUE.equals(req.force()), req.hint(), Boolean.TRUE.equals(req.textOnly()));
    }

    /** CAP-93 FR-04 最新草稿（无 → exists=false，同报告口径）。 */
    @GetMapping("/suggest/draft")
    public OkrDraftView okrDraft(@RequestParam String period) {
        return okrSuggestService.latestDraft(identity.currentActor(), period);
    }

    /** CAP-93 FR-04 采纳草稿：两阶段建 O→KR（单事务，复用目标校验链）。 */
    @PostMapping("/suggest/adopt")
    public OkrAdoptResult adoptOkr(@Valid @RequestBody OkrAdoptRequest req) {
        return okrSuggestService.adopt(identity.currentActor(), req);
    }

    /** CAP-93 FR-04 丢弃草稿（ADOPTED 不可丢弃）。 */
    @PostMapping("/suggest/discard")
    public void discardOkr(@Valid @RequestBody OkrDraftIdRequest req) {
        okrSuggestService.discard(identity.currentActor(), req.draftId());
    }

    @PostMapping
    public GoalView create(@Valid @RequestBody GoalRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public GoalView update(@PathVariable Long id, @Valid @RequestBody GoalRequest req) {
        return service.update(id, req);
    }

    /** 删除（O 有子项 400；删 KR 清引用它的周计划项 kr_id）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 跨周期滚转（幂等：非 ACTIVE 跳过；KR 需同批携带父 O 或 krParentMap 显式指定）。 */
    @PostMapping("/carry")
    public GoalCarryResult carry(@Valid @RequestBody GoalCarryRequest req) {
        if (req.ids().size() > 100) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "单次滚转上限 100 项");
        }
        return service.carry(req.toPeriod(), req.ids(), req.krParentMap());
    }
}
