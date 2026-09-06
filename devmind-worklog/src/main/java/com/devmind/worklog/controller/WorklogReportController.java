package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.GenerateDailyRequest;
import com.devmind.worklog.dto.GenerateWeeklyRequest;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.service.ReportService;
import com.devmind.worklog.service.WorklogScheduler;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.Map;

/**
 * CAP-28 FR-05/06：日报/周报查询、手动触发生成（异步，前端轮询 GET 取草稿）、编辑确认。
 */
@RestController
@RequestMapping("/api/worklog")
public class WorklogReportController {

    private final ReportService reportService;
    private final WorklogScheduler scheduler;
    private final IdentityService identity;

    public WorklogReportController(ReportService reportService, WorklogScheduler scheduler,
                                   IdentityService identity) {
        this.reportService = reportService;
        this.scheduler = scheduler;
        this.identity = identity;
    }

    // ---------------- 日报 ----------------

    @GetMapping("/daily")
    public DailyReportView getDaily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.getDaily(identity.currentActor(),
                date != null ? date : LocalDate.now());
    }

    /** 最近 days 天（默认 14 = 两周）内有报告的日报，新日期在前。 */
    @GetMapping("/daily/recent")
    public List<DailyReportView> recentDaily(@RequestParam(defaultValue = "14") int days) {
        return reportService.recentDaily(identity.currentActor(), Math.min(Math.max(days, 1), 62));
    }

    /** 手动触发生成（异步）：先同步预检（已确认 409 / 无素材 400），再提交；已有任务在跑 → 409。 */
    @PostMapping("/daily/generate")
    public Map<String, Boolean> generateDaily(@Valid @RequestBody GenerateDailyRequest req) {
        String actor = identity.currentActor();
        boolean force = Boolean.TRUE.equals(req.force());
        reportService.precheckDaily(actor, req.date(), force);
        boolean accepted = scheduler.submitDaily(actor, req.date(), force);
        if (!accepted) {
            throw new DevMindException(ErrorCode.CONFLICT, "已有报告生成任务在跑，请稍后");
        }
        return Map.of("accepted", Boolean.TRUE, "running", scheduler.isRunning());
    }

    @PutMapping("/daily/{id}")
    public DailyReportView updateDaily(@PathVariable Long id,
                                       @Valid @RequestBody UpdateDailyRequest req) {
        return reportService.updateDaily(id, identity.currentActor(), req);
    }

    // ---------------- 周报 ----------------

    @GetMapping("/weekly")
    public WeeklyReportView getWeekly(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return reportService.getWeekly(identity.currentActor(), weekStart);
    }

    /** 最近 weeks 个周（默认 5 ≈ 一个月，含本周）有报告的周报，新周在前。 */
    @GetMapping("/weekly/recent")
    public List<WeeklyReportView> recentWeekly(@RequestParam(defaultValue = "5") int weeks) {
        return reportService.recentWeekly(identity.currentActor(), Math.min(Math.max(weeks, 1), 12));
    }

    @PostMapping("/weekly/generate")
    public Map<String, Boolean> generateWeekly(@Valid @RequestBody GenerateWeeklyRequest req) {
        String actor = identity.currentActor();
        boolean force = Boolean.TRUE.equals(req.force());
        reportService.precheckWeekly(actor, req.weekStart(), force);
        boolean accepted = scheduler.submitWeekly(actor, req.weekStart(), force);
        if (!accepted) {
            throw new DevMindException(ErrorCode.CONFLICT, "已有报告生成任务在跑，请稍后");
        }
        return Map.of("accepted", Boolean.TRUE, "running", scheduler.isRunning());
    }

    @PutMapping("/weekly/{id}")
    public WeeklyReportView updateWeekly(@PathVariable Long id,
                                         @Valid @RequestBody UpdateWeeklyRequest req) {
        return reportService.updateWeekly(id, identity.currentActor(), req);
    }
}
