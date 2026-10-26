package com.devmind.worklog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 目标新建/更新请求（CAP-91 FR-02）。period：YEAR='2026'；QUARTER='2026-Q1'。
 * parentId：KR 必填（同周期 O）；QUARTER O 可选（同年 YEAR O）；YEAR O 必空。
 * status/progress 仅更新时有效（创建恒 ACTIVE/0）；status 人工可设 ACTIVE/DONE/DROPPED，
 * CARRIED 由滚转写入。
 * framework（CAP-91 M4）：目标体系，空 = OKR（当前唯一支持，其余 400）；身份属性不可改
 * （更新时传 null = 不变，传不同值 400）。
 */
public record GoalRequest(
        @NotBlank @Size(max = 8) String scope,
        @NotBlank @Size(max = 8) String period,
        @NotBlank @Size(max = 16) String kind,
        Long parentId,
        @NotBlank @Size(max = 256) String title,
        String content,
        @Min(0) @Max(100) Integer weight,
        @Min(0) @Max(100) Integer progress,
        @Size(max = 16) String status,
        @Size(max = 16) String framework) {}
