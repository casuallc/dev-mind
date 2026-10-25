package com.devmind.changelog.controller;

import com.devmind.changelog.dto.ChangelogEntryView;
import com.devmind.changelog.dto.ChangelogPageView;
import com.devmind.changelog.service.ChangelogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * CAP-87 更新日志 REST：只读。内容来自随包分发的 CHANGELOG.md，无写接口。
 */
@RestController
@RequestMapping("/api/changelog")
public class ChangelogController {

    private final ChangelogService service;

    public ChangelogController(ChangelogService service) {
        this.service = service;
    }

    @GetMapping
    public ChangelogPageView list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, version, from, to, page, size);
    }

    @GetMapping("/{version:.+}")
    public ChangelogEntryView get(@PathVariable String version) {
        return service.get(version);
    }
}
