package com.devmind.worklog.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.service.WorklogEntryService;
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

/** CAP-28 FR-03：工作条目 CRUD（仅本人）。 */
@RestController
@RequestMapping("/api/worklog/entries")
public class WorklogEntryController {

    private final WorklogEntryService service;

    public WorklogEntryController(WorklogEntryService service) {
        this.service = service;
    }

    /** 分页列表：page 从 0 起，size 默认 20（上限 200 防全量拉取）；keyword 可选，标题模糊匹配。 */
    @GetMapping
    public EntryPage list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > 200) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "size 取值范围 1-200");
        }
        return service.list(from, to, keyword, Math.max(page, 0), size);
    }

    @PostMapping
    public EntryView create(@Valid @RequestBody EntryRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public EntryView update(@PathVariable Long id, @Valid @RequestBody EntryRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
