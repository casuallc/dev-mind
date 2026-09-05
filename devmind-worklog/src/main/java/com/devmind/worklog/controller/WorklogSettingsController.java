package com.devmind.worklog.controller;

import com.devmind.worklog.dto.SettingsRequest;
import com.devmind.worklog.dto.SettingsView;
import com.devmind.worklog.service.WorklogSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-28：个人工时开关设置。 */
@RestController
@RequestMapping("/api/worklog/settings")
public class WorklogSettingsController {

    private final WorklogSettingsService service;

    public WorklogSettingsController(WorklogSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get() {
        return service.get();
    }

    @PutMapping
    public SettingsView update(@RequestBody SettingsRequest req) {
        return service.update(req);
    }
}
