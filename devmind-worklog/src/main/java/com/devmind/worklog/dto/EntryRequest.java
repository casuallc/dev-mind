package com.devmind.worklog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 工作条目新建/更新请求（CAP-28 FR-03）。hours 小时制（0.25 步进），内部转分钟存储。
 *  planId（CAP-88 FR-04）：可选关联计划项（仅 WEEK 项）；null=不关联（更新即解除）。
 *  CAP-90 FR-01/02：status 可选（默认 DONE=事实；TODO/DOING=计划态；CARRIED 人工不可设 400）；
 *  estimateHours 为计划态预估（可空，0.25 步进），「完成」交互预填确认用。 */
public record EntryRequest(
        @NotNull LocalDate workDate,
        @NotBlank @Size(max = 256) String title,
        String content,
        @Size(max = 32) String entryType,
        @NotNull @DecimalMin("0") @DecimalMax("24") Double hours,
        @Size(max = 64) String requirementId,
        @Size(max = 64) String jiraIssueKey,
        Long planId,
        @Size(max = 16) String status,
        @DecimalMin("0") @DecimalMax("24") Double estimateHours) {}
