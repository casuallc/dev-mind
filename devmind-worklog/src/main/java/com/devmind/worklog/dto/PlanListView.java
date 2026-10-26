package com.devmind.worklog.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划项列表响应（CAP-88 FR-05）：本周期计划项 + 上一周期未完成（TODO/DOING）提示数据。
 * carryoverFrom = 上一周期日期（DAY=昨日；WEEK=上周一），无未完成项时 carryoverItems 为空表。
 */
public record PlanListView(List<PlanItemView> items, LocalDate carryoverFrom,
                           List<PlanItemView> carryoverItems) {}
