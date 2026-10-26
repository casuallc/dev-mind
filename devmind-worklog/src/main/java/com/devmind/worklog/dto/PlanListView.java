package com.devmind.worklog.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 计划项列表响应（CAP-88 FR-05）：本周期计划项 + 上一周期未完成（TODO/DOING）提示数据。
 * carryoverFrom = 上一周期日期（DAY=昨日；WEEK=上周一），无未完成项时 carryoverItems 为空表。
 * krTitles（CAP-91 FR-05）：本响应涉及 KR 的 id→标题映射（避免前端逐行解析）。
 */
public record PlanListView(List<PlanItemView> items, LocalDate carryoverFrom,
                           List<PlanItemView> carryoverItems, Map<Long, String> krTitles) {}
