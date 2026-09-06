package com.devmind.worklog.dto;

import java.util.List;

/**
 * 工作条目分页响应。
 *
 * @param totalMinutes 范围内（非当前页）工时合计，前端统计卡片用，分页后不失真
 */
public record EntryPage(List<EntryView> items, long total, long totalMinutes) {}
