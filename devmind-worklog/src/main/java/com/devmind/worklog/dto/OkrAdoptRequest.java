package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * CAP-93 FR-04 草稿采纳请求：objectives 为人工确认/编辑后的树（未勾选的行不下发）。
 * scope/period 锁定草稿的（身份属性，请求里不传）。
 */
public record OkrAdoptRequest(@NotNull Long draftId,
                              @NotEmpty List<SuggestedObjective> objectives) {
}
