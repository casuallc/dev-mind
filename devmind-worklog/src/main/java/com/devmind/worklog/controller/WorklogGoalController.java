package com.devmind.worklog.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.GoalCarryRequest;
import com.devmind.worklog.dto.GoalCarryResult;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.LinkableKrView;
import com.devmind.worklog.service.WorklogGoalService;
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

/** CAP-91 目标 REST（仅本人数据，owner 强制，越权 404 同条目/计划项口径）。 */
@RestController
@RequestMapping("/api/worklog/goals")
public class WorklogGoalController {

    private final WorklogGoalService service;

    public WorklogGoalController(WorklogGoalService service) {
        this.service = service;
    }

    /** 整年目标树：年度 O（嵌套年度 KR）+ Q1~Q4 四季区（O 嵌套 KR，KR 附派生进度数据）。 */
    @GetMapping("/tree")
    public GoalTreeView tree(@RequestParam int year) {
        return service.tree(year);
    }

    /** 计划项抽屉可关联 KR：该日所在季的 ACTIVE KEY_RESULT。 */
    @GetMapping("/linkable-krs")
    public List<LinkableKrView> linkableKrs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.linkableKrs(date);
    }

    @PostMapping
    public GoalView create(@Valid @RequestBody GoalRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public GoalView update(@PathVariable Long id, @Valid @RequestBody GoalRequest req) {
        return service.update(id, req);
    }

    /** 删除（O 有子项 400；删 KR 清引用它的周计划项 kr_id）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 跨周期滚转（幂等：非 ACTIVE 跳过；KR 需同批携带父 O 或 krParentMap 显式指定）。 */
    @PostMapping("/carry")
    public GoalCarryResult carry(@Valid @RequestBody GoalCarryRequest req) {
        if (req.ids().size() > 100) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "单次滚转上限 100 项");
        }
        return service.carry(req.toPeriod(), req.ids(), req.krParentMap());
    }
}
