package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

/** CAP-93 FR-04 草稿 id 寻址请求（丢弃用）。 */
public record OkrDraftIdRequest(@NotNull Long draftId) {
}
