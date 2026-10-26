package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 目标跨周期滚转请求（CAP-91 FR-04）：把 ids 各项目标滚转到 toPeriod
 * （YEAR→'2027'；QUARTER→'2027-Q1'，与源项 scope 格式各自匹配）。
 * krParentMap：KR 新父显式指定（krId → 目标周期 O id）；缺省回退同批携带父 O 的副本，
 * 皆无 400。
 */
public record GoalCarryRequest(
        @NotBlank @Size(max = 8) String toPeriod,
        @NotEmpty List<Long> ids,
        Map<Long, Long> krParentMap) {}
