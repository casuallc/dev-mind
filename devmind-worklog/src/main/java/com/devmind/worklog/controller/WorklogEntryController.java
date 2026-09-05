package com.devmind.worklog.controller;

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
import java.util.List;

/** CAP-28 FR-03：工作条目 CRUD（仅本人）。 */
@RestController
@RequestMapping("/api/worklog/entries")
public class WorklogEntryController {

    private final WorklogEntryService service;

    public WorklogEntryController(WorklogEntryService service) {
        this.service = service;
    }

    @GetMapping
    public List<EntryView> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(from, to);
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
