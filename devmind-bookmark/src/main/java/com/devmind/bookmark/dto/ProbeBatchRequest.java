package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** FR-04 批量探测入参（ids 上限由 devmind.bookmark.probe.batch-limit 控制，默认 200）。 */
public record ProbeBatchRequest(@NotEmpty(message = "至少选择一条收藏") List<Long> ids) {
}
