package com.devmind.worklog.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.CarryRequest;
import com.devmind.worklog.dto.CarryResult;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.service.WorklogPlanService;
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

/**
 * CAP-88 计划项 REST（仅本人数据，owner 强制）。scope=DAY 时 date=当天日期；
 * scope=WEEK 时传周内任意日（服务端归一到周一，weekStart 与 date 同参复用）。
 */
@RestController
@RequestMapping("/api/worklog/plans")
public class WorklogPlanController {

    private final WorklogPlanService service;

    public WorklogPlanController(WorklogPlanService service) {
        this.service = service;
    }

    /** 计划项列表（附上一周期未完成提示数据）。 */
    @GetMapping
    public PlanListView list(@RequestParam String scope,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.list(scope, date);
    }

    /** 条目抽屉可关联项：当日日计划 + 当周周计划（DOING/DONE 也可选——补录忘挂的执行）。 */
    @GetMapping("/linkable")
    public List<PlanItemView> linkable(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.linkable(date);
    }

    @PostMapping("/items")
    public PlanItemView create(@Valid @RequestBody PlanItemRequest req) {
        return service.create(req);
    }

    @PutMapping("/items/{id}")
    public PlanItemView update(@PathVariable Long id, @Valid @RequestBody PlanItemRequest req) {
        return service.update(id, req);
    }

    /** 删除（关联条目 plan_item_id 置空）。 */
    @DeleteMapping("/items/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 一键滚转到目标周期（幂等：已 CARRIED/已完结跳过）。 */
    @PostMapping("/carry")
    public CarryResult carry(@Valid @RequestBody CarryRequest req) {
        if (req.ids().size() > 200) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "单次滚转上限 200 项");
        }
        return service.carry(req.scope(), req.to(), req.ids());
    }
}
