package com.devmind.worklog.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.EntryCarryRequest;
import com.devmind.worklog.dto.EntryCarryResult;
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
import java.util.List;

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
    public EntryView update(@PathVariable String id, @Valid @RequestBody EntryRequest req) {
        return service.update(id, req);
    }

    /** CAP-89 FR-06：删除按 DB 主键寻址（CAP-74 的 /{date}/{id} 日期寻址是文件时代限制，已回收）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** CAP-90 FR-05：上一日未完成（TODO/DOING）条目提示（当日清单顶部的滚转提示数据源）。 */
    @GetMapping("/carryover")
    public List<EntryView> carryover(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dayCarryover(date);
    }

    /** CAP-90 FR-05：日粒度一键滚转（幂等：已 CARRIED/已完结跳过；原行置 CARRIED）。 */
    @PostMapping("/carry")
    public EntryCarryResult carry(@Valid @RequestBody EntryCarryRequest req) {
        if (req.ids().size() > 200) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "单次滚转上限 200 项");
        }
        return service.carry(req.to(), req.ids());
    }
}
