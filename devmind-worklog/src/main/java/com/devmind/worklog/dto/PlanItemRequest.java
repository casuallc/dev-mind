package com.devmind.worklog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 计划项新建/更新请求（CAP-88 FR-02）。date：DAY=当天日期；WEEK=周内任意日（服务端归一到周一）。
 * hours 为预估工时（0.25 步进，可空=未预估），内部转分钟存储。
 * status 仅更新时有效（创建恒 TODO）；人工可设 TODO/DOING/DONE/DROPPED，CARRIED 仅滚转写入。
 */
public record PlanItemRequest(
        @NotBlank @Size(max = 8) String scope,
        @NotNull LocalDate date,
        @NotBlank @Size(max = 256) String title,
        String content,
        @Size(max = 32) String planType,
        @DecimalMin("0") @DecimalMax("24") Double hours,
        @Size(max = 64) String requirementId,
        @Size(max = 64) String jiraIssueKey,
        @Size(max = 16) String status) {}
