package com.devmind.usage.controller;

import com.devmind.common.dto.PageView;
import com.devmind.usage.dto.UsageBreakdownRow;
import com.devmind.usage.dto.UsageDailyPoint;
import com.devmind.usage.dto.UsageHourlyPoint;
import com.devmind.usage.dto.UsageRecordRow;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.dto.UsageTopRow;
import com.devmind.usage.service.UsageRecordQueryService;
import com.devmind.usage.service.UsageStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * CAP-67 用量统计端点。全部只读；可见范围由服务层收口（非 admin 强制本人，admin 缺省全部）。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageStatsService service;
    private final UsageRecordQueryService recordQueryService;

    public UsageController(UsageStatsService service, UsageRecordQueryService recordQueryService) {
        this.service = service;
        this.recordQueryService = recordQueryService;
    }

    @GetMapping("/summary")
    public UsageSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String userId) {
        return service.summary(from, to, userId);
    }

    @GetMapping("/breakdown")
    public List<UsageBreakdownRow> breakdown(
            @RequestParam String dim,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String userId) {
        return service.breakdown(dim, from, to, userId);
    }

    @GetMapping("/daily")
    public List<UsageDailyPoint> daily(@RequestParam(defaultValue = "30") int days,
                                       @RequestParam(required = false) String userId) {
        return service.daily(Math.max(1, Math.min(days, 90)), userId);
    }

    @GetMapping("/top")
    public List<UsageTopRow> top(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String userId) {
        return service.top(Math.max(1, Math.min(limit, 100)), from, to, userId);
    }

    /** 请求日志：逐回合账本时间倒序分页（账本只含上线后的新数据，不回填存量）。 */
    @GetMapping("/records")
    public PageView<UsageRecordRow> records(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String userId) {
        return recordQueryService.records(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                source, model, from, to, userId);
    }

    /** 今日按小时趋势（服务器时区 24 桶含零桶；与 daily 的「按创建日归属」口径不同，属精确回合时刻归属）。 */
    @GetMapping("/hourly")
    public List<UsageHourlyPoint> hourly(@RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String model) {
        return recordQueryService.hourly(userId, model);
    }
}
