package com.devmind.usage.controller;

import com.devmind.usage.dto.UsageBreakdownRow;
import com.devmind.usage.dto.UsageDailyPoint;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.dto.UsageTopRow;
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

    public UsageController(UsageStatsService service) {
        this.service = service;
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
}
