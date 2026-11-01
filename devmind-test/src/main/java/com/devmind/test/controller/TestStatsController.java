package com.devmind.test.controller;

import com.devmind.test.dto.TestStatsView;
import com.devmind.test.service.TestStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAP-104 FR-21 统计：趋势 / Top 失败 / flaky / 耗时 / 环境对比。 */
@RestController
@RequestMapping("/api")
public class TestStatsController {

    private final TestStatsService service;

    public TestStatsController(TestStatsService service) {
        this.service = service;
    }

    @GetMapping("/projects/{id}/test-stats")
    public TestStatsView stats(@PathVariable String id,
                               @RequestParam(required = false) Integer days,
                               @RequestParam(required = false) String granularity,
                               @RequestParam(required = false) Integer minSamples,
                               @RequestParam(required = false) Long planId,
                               @RequestParam(required = false) Long environmentId,
                               @RequestParam(required = false) Integer topLimit) {
        return service.stats(id, days, granularity, minSamples, planId, environmentId, topLimit);
    }
}
