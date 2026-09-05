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

    /** 手动触发生成（异步）：返回 {accepted, running}；已有任务在跑 → 409。 */
    @PostMapping("/daily/generate")
    public Map<String, Boolean> generateDaily(@Valid @RequestBody GenerateDailyRequest req) {
        boolean accepted = scheduler.submitDaily(identity.currentActor(), req.date(),
                Boolean.TRUE.equals(req.force()));
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

    @PostMapping("/weekly/generate")
    public Map<String, Boolean> generateWeekly(@Valid @RequestBody GenerateWeeklyRequest req) {
        boolean accepted = scheduler.submitWeekly(identity.currentActor(), req.weekStart(),
                Boolean.TRUE.equals(req.force()));
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
