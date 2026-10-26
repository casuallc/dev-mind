package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** 条目日粒度滚转请求（CAP-90 FR-05）：把 ids 各条目滚转到目标日期（副本 status=TODO，原行置 CARRIED）。 */
public record EntryCarryRequest(
        @NotNull LocalDate to,
        @NotEmpty List<String> ids) {}
