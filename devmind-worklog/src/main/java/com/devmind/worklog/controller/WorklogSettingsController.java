package com.devmind.worklog.controller;

import com.devmind.worklog.dto.SettingsRequest;
import com.devmind.worklog.dto.SettingsView;
import com.devmind.worklog.service.WorklogSettingsService;
import com.devmind.worklog.service.WorklogTemplates;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-28：个人工时开关设置；CAP-41 FR-05：日报/周报格式模板（设置项 + 内置默认查询）。 */
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

    /** 内置默认模板（前端模板编辑器「填入默认」用；自定义清空即回退到此）。 */
    @GetMapping("/templates/default")
    public TemplateDefaultsView defaultTemplates() {
        return new TemplateDefaultsView(WorklogTemplates.DEFAULT_DAILY, WorklogTemplates.DEFAULT_WEEKLY);
    }

    public record TemplateDefaultsView(String dailyTemplateMd, String weeklyTemplateMd) {}
}
