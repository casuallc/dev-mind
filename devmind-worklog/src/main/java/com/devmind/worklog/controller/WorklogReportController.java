package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.worklog.dto.CreateDailyRequest;
import com.devmind.worklog.dto.CreateWeeklyRequest;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.GenerateDailyRequest;
import com.devmind.worklog.dto.GenerateWeeklyRequest;
import com.devmind.worklog.dto.ReportLaunch;
import com.devmind.worklog.dto.SyncReportsRequest;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.service.ReportService;
import com.devmind.worklog.service.WorklogOutputMirror;
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

/**
 * CAP-28 FR-05/06：日报/周报查询、手动创建空白稿、编辑保存；
 * CAP-41 FR-03：AI 触发生成 = 创建 worklog 会话（同步受理返回 sessionId，前端轮询报告）；
 * CAP-74：编辑保存按日期/周一寻址（PUT /daily/{date}、/weekly/{weekStart}），状态机删除。
 */
@RestController
@RequestMapping("/api/worklog")
public class WorklogReportController {

    private final ReportService reportService;
    private final IdentityService identity;
    private final WorklogOutputMirror outputMirror;

    public WorklogReportController(ReportService reportService, IdentityService identity,
                                   WorklogOutputMirror outputMirror) {
        this.reportService = reportService;
        this.identity = identity;
        this.outputMirror = outputMirror;
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

    /** 某周（weekStart=周一）7 天内有报告的日报，日期升序；日报周视图的周日选择条用。 */
    @GetMapping("/daily/week")
    public List<DailyReportView> weekDaily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return reportService.weekDaily(identity.currentActor(), weekStart);
    }

    /** 手动触发生成（CAP-41：创建 worklog 会话，立即返回 sessionId；成稿随会话结束回传写仓）。 */
    @PostMapping("/daily/generate")
    public ReportLaunch generateDaily(@Valid @RequestBody GenerateDailyRequest req) {
        String actor = identity.currentActor();
        boolean force = Boolean.TRUE.equals(req.force());
        reportService.precheckDaily(actor, req.date(), force);
        return reportService.generateDaily(actor, req.date(), force, req.hint());
    }

    /** 手动创建空白日报（不经 AI、不要求素材）：已存在幂等返回。 */
    @PostMapping("/daily")
    public DailyReportView createDaily(@Valid @RequestBody CreateDailyRequest req) {
        return reportService.createDaily(identity.currentActor(), req.date());
    }

    /** CAP-74：按日期寻址保存（保存即生效写仓 + commit）；status 字段已废弃，传了 400。 */
    @PutMapping("/daily/{date}")
    public DailyReportView updateDaily(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody UpdateDailyRequest req) {
        return reportService.updateDaily(identity.currentActor(), date, req);
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
    public ReportLaunch generateWeekly(@Valid @RequestBody GenerateWeeklyRequest req) {
        String actor = identity.currentActor();
        boolean force = Boolean.TRUE.equals(req.force());
        reportService.precheckWeekly(actor, req.weekStart(), force);
        return reportService.generateWeekly(actor, req.weekStart(), force, req.hint());
    }

    /** 手动创建空白周报：同 POST /daily，幂等。 */
    @PostMapping("/weekly")
    public WeeklyReportView createWeekly(@Valid @RequestBody CreateWeeklyRequest req) {
        return reportService.createWeekly(identity.currentActor(), req.weekStart());
    }

    /** CAP-74：按周一寻址保存（分节字段重组正文写仓）；status 传了 400。 */
    @PutMapping("/weekly/{weekStart}")
    public WeeklyReportView updateWeekly(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @Valid @RequestBody UpdateWeeklyRequest req) {
        return reportService.updateWeekly(identity.currentActor(), weekStart, req);
    }

    // ---------------- 会话成稿同步 ----------------

    /**
     * 把工作日志空间某会话的成稿产出（.devmind/output 的 daily-/weekly- 文件）
     * 写进服务端仓（幂等；同内容零 commit）。前端对话页「推送工作日志」用，
     * 调用前通常先 POST /sessions/{id}/outputs/collect 让 runner 即时回传。
     */
    @PostMapping("/reports/sync")
    public com.devmind.worklog.dto.WorklogSyncResult syncReports(@Valid @RequestBody SyncReportsRequest req) {
        return outputMirror.syncSession(req.sessionId(), identity.currentActor());
    }
}
