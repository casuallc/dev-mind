package com.devmind.worklog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 工作条目新建/更新请求（CAP-28 FR-03）。hours 小时制（0.25 步进），内部转分钟存储。
 *  planId（CAP-88 FR-04）：可选关联计划项；null=不关联（更新即解除），关联触发 TODO→DOING 联动。 */
public record EntryRequest(
        @NotNull LocalDate workDate,
        @NotBlank @Size(max = 256) String title,
        String content,
        @Size(max = 32) String entryType,
        @NotNull @DecimalMin("0") @DecimalMax("24") Double hours,
        @Size(max = 64) String requirementId,
        @Size(max = 64) String jiraIssueKey,
        Long planId) {}
